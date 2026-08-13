/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
#include <pybind11/pybind11.h>
#include <mutex>

namespace py = pybind11;

class CppToPythonLogger {
public:
    CppToPythonLogger();

    static CppToPythonLogger* get();

    void setLogger(py::object& logger);

    py::object getLogger();

private:
    py::object logger_;
    std::mutex loggerMutex_;
};

// Log level of the python logger, cached so that java calls do not have to acquire the GIL to read
// it. Negative when no logger is set. refreshCachedPythonLogLevel must be called with the GIL held.
int cachedPythonLogLevel();

void refreshCachedPythonLogLevel();

// Marked by the callbacks running python code while a java call is in progress, so that the
// post-call hook only looks for a pending python error when one of them has actually run.
void markPythonCallbackRun();

bool takePythonCallbackRun();

void logFromJava(int level, long timestamp, char* loggerName, char* message);

void setLogger(py::object& logger);

py::object getLogger();
