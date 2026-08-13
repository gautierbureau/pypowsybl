/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
#include "pylogging.h"
#include "powsybl-cpp.h"
#include <atomic>
#include <iostream>

using namespace pybind11::literals;

namespace {

std::atomic<int> cachedLogLevel(-1);

// consumed by the post-call hook of the calling thread; python errors are per thread anyway
thread_local bool pythonCallbackRun = false;

}

// function local static: initialized once, thread safely, without locking on every call
CppToPythonLogger* CppToPythonLogger::get() {
    static CppToPythonLogger singleton;
    return &singleton;
}

int cachedPythonLogLevel() {
    return cachedLogLevel.load(std::memory_order_relaxed);
}

void refreshCachedPythonLogLevel() {
    py::object logger = CppToPythonLogger::get()->getLogger();
    cachedLogLevel.store(logger.is_none() ? -1 : logger.attr("level").cast<int>(), std::memory_order_relaxed);
}

void markPythonCallbackRun() {
    pythonCallbackRun = true;
}

bool takePythonCallbackRun() {
    bool run = pythonCallbackRun;
    pythonCallbackRun = false;
    return run;
}

CppToPythonLogger::CppToPythonLogger()
    : logger_(py::none()) {
}

void CppToPythonLogger::setLogger(py::object& logger) {
    std::lock_guard<std::mutex> guard(loggerMutex_);
    logger_ = logger;
}

py::object CppToPythonLogger::getLogger() {
    std::lock_guard<std::mutex> guard(loggerMutex_);
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
    markPythonCallbackRun();
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
    refreshCachedPythonLogLevel(); // called from python, the GIL is held
    auto fptr = &::logFromJava;
    pypowsybl::setupLoggerCallback(reinterpret_cast<void *&>(fptr));
}

py::object getLogger() {
    return CppToPythonLogger::get()->getLogger();
}
