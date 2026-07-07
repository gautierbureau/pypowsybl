/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
#include "pylogging.h"
#include "powsybl-cpp.h"
#include <iostream>

using namespace pybind11::literals;

CppToPythonLogger* CppToPythonLogger::get() {
    // Meyers singleton: thread-safe lazy initialization without a per-call mutex lock.
    static CppToPythonLogger instance;
    return &instance;
}

CppToPythonLogger::CppToPythonLogger()
    : logger_(py::none()) {
}

// logger_ is only ever accessed with the Python GIL held (setLogger and getLogger run either from
// Python code or from callbacks that acquire the GIL first), so the GIL already serializes access and
// no additional mutex is needed. getLogger() was previously taking a global lock on every Java call.
void CppToPythonLogger::setLogger(py::object& logger) {
    logger_ = logger;
}

py::object CppToPythonLogger::getLogger() {
    return logger_;
}

/// Saves error and restores it at the end of the scope,
/// unless another one has been set in the meantime.
struct save_python_error {
    PyObject *type, *value, *trace;
    save_python_error() { PyErr_Fetch(&type, &value, &trace); }

    ~save_python_error() {
        if (PyErr_Occurred() == nullptr) {
            PyErr_Restore(type, value, trace); 
        }
    }
};

void logFromJava(int level, long timestamp, char* loggerName, char* message) {
    py::gil_scoped_acquire acquire;
    save_python_error previousError;  // to keep and restore the previously set exception, if any
    py::object logger = CppToPythonLogger::get()->getLogger();
    if (!logger.is_none()) {
        try {
          py::dict d("java_logger_name"_a=loggerName, "java_timestamp"_a=timestamp);
          CppToPythonLogger::get()->getLogger().attr("log")(level, message, "extra"_a=d);
        } catch (py::error_already_set& err) {
          err.restore();
        }
    }
}

void setLogger(py::object& logger) {
    CppToPythonLogger::get()->setLogger(logger);
    auto fptr = &::logFromJava;
    pypowsybl::setupLoggerCallback(reinterpret_cast<void *&>(fptr));
}

py::object getLogger() {
    return CppToPythonLogger::get()->getLogger();
}
