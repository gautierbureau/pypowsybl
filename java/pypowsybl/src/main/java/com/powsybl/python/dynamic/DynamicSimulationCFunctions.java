/**
 * Copyright (c) 2020-2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.dynamic;

import static com.powsybl.python.commons.CTypeUtil.toStringList;
import static com.powsybl.python.commons.Util.*;
import static com.powsybl.python.dynamic.DynamicSimulationParametersCUtils.*;
import static com.powsybl.python.network.NetworkCFunctions.createDataframe;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.powsybl.commons.parameters.Parameter;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.dataframe.SeriesMetadata;
import com.powsybl.dataframe.dynamic.DynamicSimulationDataframeMappersUtils;
import com.powsybl.dataframe.dynamic.TimeSeriesConverter;
import com.powsybl.python.commons.PyPowsyblApiHeader;
import com.powsybl.python.network.Dataframes;
import com.powsybl.python.report.ReportCUtils;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.ObjectHandle;
import org.graalvm.nativeimage.ObjectHandles;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CCharPointerPointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.powsybl.dataframe.dynamic.adders.DynamicMappingHandler;
import com.powsybl.dataframe.dynamic.adders.EventMappingHandler;
import com.powsybl.dataframe.update.UpdatingDataframe;
import com.powsybl.dynamicsimulation.OutputVariablesSupplier;
import com.powsybl.dynamicsimulation.DynamicSimulationParameters;
import com.powsybl.dynamicsimulation.DynamicSimulationResult;
import com.powsybl.dynamicsimulation.EventModelsSupplier;
import com.powsybl.dynawo.DynawoSimulationParameters;
import com.powsybl.dynawo.mappings.DynamicModelsMappings;
import com.powsybl.dynawo.mappings.MappingParameters;
import com.powsybl.dynawo.mappings.DynamicMappingExtensions;
import com.powsybl.dynawo.mappings.DynamicSimulationSystems;
import com.powsybl.dynawo.mappings.SynchronousGeneratorPropertiesProviders;
import com.powsybl.dynawo.mappings.TapChangerBlockingsProviders;
import com.powsybl.dynawo.models.BlackBoxModel;
import com.powsybl.dynawo.parameters.ParametersSet;
import com.powsybl.dynawo.xml.ParametersXml;
import com.powsybl.iidm.network.Network;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import com.powsybl.python.commons.CTypeUtil;
import com.powsybl.python.commons.Directives;
import com.powsybl.python.commons.PyPowsyblApiHeader.ArrayPointer;
import com.powsybl.python.commons.PyPowsyblApiHeader.DataframeMetadataPointer;
import com.powsybl.python.commons.PyPowsyblApiHeader.DataframePointer;
import com.powsybl.python.commons.PyPowsyblApiHeader.SeriesPointer;
import com.powsybl.python.commons.Util;
import com.powsybl.python.dynamic.criteria.CriteriaDataframeAdder;
import com.powsybl.python.dynamic.criteria.PythonCriteria;

import static com.powsybl.python.commons.PyPowsyblApiHeader.*;

/**
 * @author Nicolas Pierre {@literal <nicolas.pierre@artelys.com>}
 */
@CContext(Directives.class)
public final class DynamicSimulationCFunctions {

    private DynamicSimulationCFunctions() {
    }

    private static Logger logger() {
        return LoggerFactory.getLogger(DynamicSimulationCFunctions.class);
    }

    @CEntryPoint(name = "createDynamicSimulationContext")
    public static ObjectHandle createDynamicSimulationContext(IsolateThread thread,
            ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, () -> ObjectHandles.getGlobal().create(new DynamicSimulationContext()));
    }

    @CEntryPoint(name = "createDynamicModelMapping")
    public static ObjectHandle createDynamicModelMapping(IsolateThread thread,
            ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, () -> ObjectHandles.getGlobal().create(new PythonDynamicModelsSupplier()));
    }

    @CEntryPoint(name = "addMappingRecipe")
    public static void addMappingRecipe(IsolateThread thread, ObjectHandle dynamicMappingHandle,
                                        CCharPointer mappingNamePtr,
                                        CCharPointerPointer parameterNamesPtr, int parameterNamesCount,
                                        CCharPointerPointer parameterValuesPtr, int parameterValuesCount,
                                        ExceptionHandlerPointer exceptionHandlerPtr) {
        // an explicit class rather than a lambda: the handles and the pointers are word values,
        // which a lambda cannot capture
        doCatch(exceptionHandlerPtr, new Runnable() {
            @Override
            public void run() {
                PythonDynamicModelsSupplier supplier = ObjectHandles.getGlobal().get(dynamicMappingHandle);
                String mappingName = CTypeUtil.toString(mappingNamePtr);
                // a recipe carries no network: it is the name of a registered mapping and the
                // settings it takes, applied later against whatever network the models are asked
                // for, so a mapping is named the same lazy way the dataframe adders already are
                Map<String, String> settings = CTypeUtil.toStringMap(parameterNamesPtr, parameterNamesCount,
                        parameterValuesPtr, parameterValuesCount);
                supplier.addMappingRecipe(mappingName, MappingParameters.of(settings));
            }
        });
    }

    @CEntryPoint(name = "getDynamicMappingProviders")
    public static ArrayPointer<CCharPointerPointer> getDynamicMappingProviders(IsolateThread thread,
            ExceptionHandlerPointer exceptionHandlerPtr) {
        // one line per registered mapping, its name and its description tab apart, which is all
        // that has to cross for a caller to see what it can be given and choose one
        return doCatch(exceptionHandlerPtr, () -> Util.createCharPtrArray(
                DynamicModelsMappings.getInstance().getMappingInfos().stream()
                        .map(info -> info.name() + "\t" + info.description())
                        .toList()));
    }

    @CEntryPoint(name = "addSynchronousGeneratorProperties")
    public static void addSynchronousGeneratorProperties(IsolateThread thread, ObjectHandle networkHandle,
                                                         CCharPointer providerNamePtr,
                                                         CCharPointerPointer parameterNamesPtr, int parameterNamesCount,
                                                         CCharPointerPointer parameterValuesPtr, int parameterValuesCount,
                                                         ExceptionHandlerPointer exceptionHandlerPtr) {
        doCatch(exceptionHandlerPtr, new Runnable() {
            @Override
            public void run() {
                Network network = ObjectHandles.getGlobal().get(networkHandle);
                String providerName = CTypeUtil.toString(providerNamePtr);
                // the controls a mapping reads are written here as a step of its own, from a named
                // provider given the study's settings, so they can be set and looked at before a
                // model is chosen for them. A machine already described is left as it is
                Map<String, String> settings = CTypeUtil.toStringMap(parameterNamesPtr, parameterNamesCount,
                        parameterValuesPtr, parameterValuesCount);
                SynchronousGeneratorPropertiesProviders.getInstance()
                        .createExtensions(network, providerName, MappingParameters.of(settings));
            }
        });
    }

    @CEntryPoint(name = "getSynchronousGeneratorPropertiesProviders")
    public static ArrayPointer<CCharPointerPointer> getSynchronousGeneratorPropertiesProviders(IsolateThread thread,
            ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, () -> Util.createCharPtrArray(
                SynchronousGeneratorPropertiesProviders.getInstance().getProviderInfos().stream()
                        .map(info -> info.name() + "\t" + info.description())
                        .toList()));
    }

    @CEntryPoint(name = "addTapChangerBlockings")
    public static void addTapChangerBlockings(IsolateThread thread, ObjectHandle networkHandle,
                                              CCharPointer providerNamePtr,
                                              CCharPointerPointer parameterNamesPtr, int parameterNamesCount,
                                              CCharPointerPointer parameterValuesPtr, int parameterValuesCount,
                                              ExceptionHandlerPointer exceptionHandlerPtr) {
        doCatch(exceptionHandlerPtr, new Runnable() {
            @Override
            public void run() {
                Network network = ObjectHandles.getGlobal().get(networkHandle);
                String providerName = CTypeUtil.toString(providerNamePtr);
                Map<String, String> settings = CTypeUtil.toStringMap(parameterNamesPtr, parameterNamesCount,
                        parameterValuesPtr, parameterValuesCount);
                TapChangerBlockingsProviders.getInstance()
                        .createExtensions(network, providerName, MappingParameters.of(settings));
            }
        });
    }

    @CEntryPoint(name = "getTapChangerBlockingsProviders")
    public static ArrayPointer<CCharPointerPointer> getTapChangerBlockingsProviders(IsolateThread thread,
            ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, () -> Util.createCharPtrArray(
                TapChangerBlockingsProviders.getInstance().getProviderInfos().stream()
                        .map(info -> info.name() + "\t" + info.description())
                        .toList()));
    }

    @CEntryPoint(name = "addDynamicMappingExtensions")
    public static void addDynamicMappingExtensions(IsolateThread thread, ObjectHandle networkHandle,
                                                   CCharPointer extensionNamePtr, CCharPointer providerNamePtr,
                                                   CCharPointerPointer parameterNamesPtr, int parameterNamesCount,
                                                   CCharPointerPointer parameterValuesPtr, int parameterValuesCount,
                                                   ExceptionHandlerPointer exceptionHandlerPtr) {
        doCatch(exceptionHandlerPtr, new Runnable() {
            @Override
            public void run() {
                Network network = ObjectHandles.getGlobal().get(networkHandle);
                String extensionName = CTypeUtil.toString(extensionNamePtr);
                String providerName = CTypeUtil.toString(providerNamePtr);
                Map<String, String> settings = CTypeUtil.toStringMap(parameterNamesPtr, parameterNamesCount,
                        parameterValuesPtr, parameterValuesCount);
                // the one door every kind of mapping extension is added through, the public methods
                // and the RTE side running this underneath
                DynamicMappingExtensions.getInstance()
                        .createExtensions(network, extensionName, providerName, MappingParameters.of(settings));
            }
        });
    }

    @CEntryPoint(name = "getDynamicMappingExtensionNames")
    public static ArrayPointer<CCharPointerPointer> getDynamicMappingExtensionNames(IsolateThread thread,
            ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, () -> Util.createCharPtrArray(
                new ArrayList<>(DynamicMappingExtensions.getInstance().getExtensionNames())));
    }

    @CEntryPoint(name = "getDynamicMappingExtensionProviders")
    public static ArrayPointer<CCharPointerPointer> getDynamicMappingExtensionProviders(IsolateThread thread,
            CCharPointer extensionNamePtr, ExceptionHandlerPointer exceptionHandlerPtr) {
        // read the pointer before the lambda: a Word value captured in a lambda is not supported by
        // native image, so the lambda closes over the String, not the pointer
        String extensionName = CTypeUtil.toString(extensionNamePtr);
        return doCatch(exceptionHandlerPtr, () -> Util.createCharPtrArray(
                DynamicMappingExtensions.getInstance().getProviderInfos(extensionName).stream()
                        .map(info -> info.name() + "\t" + info.description())
                        .toList()));
    }

    @CEntryPoint(name = "addDynamicSimulationExtensions")
    public static void addDynamicSimulationExtensions(IsolateThread thread, ObjectHandle networkHandle,
                                                      CCharPointer systemNamePtr,
                                                      CCharPointerPointer parameterNamesPtr, int parameterNamesCount,
                                                      CCharPointerPointer parameterValuesPtr, int parameterValuesCount,
                                                      ExceptionHandlerPointer exceptionHandlerPtr) {
        doCatch(exceptionHandlerPtr, new Runnable() {
            @Override
            public void run() {
                Network network = ObjectHandles.getGlobal().get(networkHandle);
                String systemName = CTypeUtil.toString(systemNamePtr);
                // every extension a named system reads, added at once
                Map<String, String> settings = CTypeUtil.toStringMap(parameterNamesPtr, parameterNamesCount,
                        parameterValuesPtr, parameterValuesCount);
                DynamicSimulationSystems.getInstance()
                        .createExtensions(network, systemName, MappingParameters.of(settings));
            }
        });
    }

    @CEntryPoint(name = "getDynamicSimulationSystems")
    public static ArrayPointer<CCharPointerPointer> getDynamicSimulationSystems(IsolateThread thread,
            ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, () -> Util.createCharPtrArray(
                DynamicSimulationSystems.getInstance().getSystemInfos().stream()
                        .map(info -> info.name() + "\t" + info.description())
                        .toList()));
    }

    @CEntryPoint(name = "getMappedModels")
    public static ArrayPointer<PyPowsyblApiHeader.SeriesPointer> getMappedModels(IsolateThread thread,
                                                                                 ObjectHandle dynamicMappingHandle,
                                                                                 ObjectHandle networkHandle,
                                                                                 ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<ArrayPointer<PyPowsyblApiHeader.SeriesPointer>>() {
            @Override
            public ArrayPointer<PyPowsyblApiHeader.SeriesPointer> get() {
                PythonDynamicModelsSupplier supplier = ObjectHandles.getGlobal().get(dynamicMappingHandle);
                Network network = ObjectHandles.getGlobal().get(networkHandle);
                List<BlackBoxModel> models = supplier.get(network, ReportNode.NO_OP).stream()
                        .filter(BlackBoxModel.class::isInstance)
                        .map(BlackBoxModel.class::cast)
                        .toList();
                return Dataframes.createCDataframe(DynamicSimulationDataframeMappersUtils.mappedModelsDataFrameMapper(), models);
            }
        });
    }

    @CEntryPoint(name = "getMappedParameters")
    public static ArrayPointer<PyPowsyblApiHeader.SeriesPointer> getMappedParameters(IsolateThread thread,
                                                                                     ObjectHandle dynamicMappingHandle,
                                                                                     ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<ArrayPointer<PyPowsyblApiHeader.SeriesPointer>>() {
            @Override
            public ArrayPointer<PyPowsyblApiHeader.SeriesPointer> get() {
                PythonDynamicModelsSupplier supplier = ObjectHandles.getGlobal().get(dynamicMappingHandle);
                Collection<ParametersSet> sets = supplier.getOrCreateMappingParameters().getModelParameters();
                return Dataframes.createCDataframe(DynamicSimulationDataframeMappersUtils.mappedParametersDataFrameMapper(), sets);
            }
        });
    }

    @CEntryPoint(name = "loadMappedParameters")
    public static void loadMappedParameters(IsolateThread thread, ObjectHandle dynamicMappingHandle,
                                            CCharPointer parametersFilePtr, ExceptionHandlerPointer exceptionHandlerPtr) {
        doCatch(exceptionHandlerPtr, new Runnable() {
            @Override
            public void run() {
                PythonDynamicModelsSupplier supplier = ObjectHandles.getGlobal().get(dynamicMappingHandle);
                String parametersFile = CTypeUtil.toString(parametersFilePtr);
                supplier.getOrCreateMappingParameters()
                        .setModelsParameters(ParametersXml.load(Path.of(parametersFile)));
            }
        });
    }

    @CEntryPoint(name = "getParameterCompletions")
    public static ArrayPointer<PyPowsyblApiHeader.SeriesPointer> getParameterCompletions(IsolateThread thread,
                                                                                         ObjectHandle dynamicMappingHandle,
                                                                                         ObjectHandle networkHandle,
                                                                                         ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<ArrayPointer<PyPowsyblApiHeader.SeriesPointer>>() {
            @Override
            public ArrayPointer<PyPowsyblApiHeader.SeriesPointer> get() {
                PythonDynamicModelsSupplier supplier = ObjectHandles.getGlobal().get(dynamicMappingHandle);
                Network network = ObjectHandles.getGlobal().get(networkHandle);
                supplier.get(network, ReportNode.NO_OP);
                return Dataframes.createCDataframe(DynamicSimulationDataframeMappersUtils.parameterCompletionsDataFrameMapper(),
                        supplier.getCompletions());
            }
        });
    }

    @CEntryPoint(name = "updateMappedParameter")
    public static void updateMappedParameter(IsolateThread thread, ObjectHandle dynamicMappingHandle,
                                             CCharPointer parameterSetIdPtr, CCharPointer parameterNamePtr,
                                             CCharPointer valuePtr, ExceptionHandlerPointer exceptionHandlerPtr) {
        doCatch(exceptionHandlerPtr, new Runnable() {
            @Override
            public void run() {
                PythonDynamicModelsSupplier supplier = ObjectHandles.getGlobal().get(dynamicMappingHandle);
                String parameterSetId = CTypeUtil.toString(parameterSetIdPtr);
                String parameterName = CTypeUtil.toString(parameterNamePtr);
                String value = CTypeUtil.toString(valuePtr);
                // the change is made now if the set is there, or held until the recipe that writes
                // it is applied to a network, so a value can be set before the mapping is resolved
                supplier.updateParameterValue(parameterSetId, parameterName, value);
            }
        });
    }

    @CEntryPoint(name = "createTimeseriesMapping")
    public static ObjectHandle createTimeseriesMapping(IsolateThread thread,
            ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, () -> ObjectHandles.getGlobal().create(new PythonOutputVariablesSupplier()));
    }

    @CEntryPoint(name = "createEventMapping")
    public static ObjectHandle createEventMapping(IsolateThread thread,
            ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, () -> ObjectHandles.getGlobal().create(new PythonEventModelsSupplier()));
    }

    @CEntryPoint(name = "createDynamicSimulationParameters")
    public static DynamicSimulationParametersPointer createDynamicSimulationParameters(IsolateThread thread, ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, () -> {
            DynamicSimulationParametersPointer paramsPtr = UnmanagedMemory.calloc(SizeOf.get(DynamicSimulationParametersPointer.class));
            copyToCDynamicSimulationParameters(paramsPtr);
            return paramsPtr;
        });
    }

    @CEntryPoint(name = "freeDynamicSimulationParameters")
    public static void freeDynamicSimulationParameters(IsolateThread thread, DynamicSimulationParametersPointer parametersPtr,
                                              ExceptionHandlerPointer exceptionHandlerPtr) {
        doCatch(exceptionHandlerPtr, new Runnable() {
            @Override
            public void run() {
                freeProviderParameters(parametersPtr.getProviderParameters());
                UnmanagedMemory.free(parametersPtr);
            }
        });
    }

    @CEntryPoint(name = "runDynamicSimulation")
    public static ObjectHandle runDynamicSimulation(IsolateThread thread,
                                                    ObjectHandle dynamicContextHandle,
                                                    ObjectHandle networkHandle,
                                                    ObjectHandle dynamicMappingHandle,
                                                    ObjectHandle eventModelsSupplierHandle,
                                                    ObjectHandle outputVariablesSupplierHandle,
                                                    ObjectHandle criteriaHandle,
                                                    DynamicSimulationParametersPointer parametersPtr,
                                                    ObjectHandle reportNodeHandle,
                                                    ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<ObjectHandle>() {
            @Override
            public ObjectHandle get() throws IOException {
                DynamicSimulationContext dynamicContext = ObjectHandles.getGlobal().get(dynamicContextHandle);
                Network network = ObjectHandles.getGlobal().get(networkHandle);
                PythonDynamicModelsSupplier dynamicMapping = ObjectHandles.getGlobal().get(dynamicMappingHandle);
                EventModelsSupplier eventModelsSupplier = ObjectHandles.getGlobal().get(eventModelsSupplierHandle);
                if (eventModelsSupplier == null) {
                    eventModelsSupplier = EventModelsSupplier.empty();
                }
                OutputVariablesSupplier outputVariablesSupplier = ObjectHandles.getGlobal().get(outputVariablesSupplierHandle);
                if (outputVariablesSupplier == null) {
                    outputVariablesSupplier = OutputVariablesSupplier.empty();
                }
                ReportNode reportNode = ReportCUtils.getReportNode(reportNodeHandle);
                if (reportNode == null) {
                    reportNode = ReportNode.NO_OP;
                }
                // the study's own provider parameters (symbolicJacobian, precision, modelSimplifiers, ...),
                // already applied to the parameters built here, are kept so they can be re-applied on top
                // of the mapping's run parameters below, which would otherwise replace the whole extension
                Map<String, String> providerParameters =
                        DynamicSimulationParametersCUtils.getSpecificParameters(parametersPtr);
                DynamicSimulationParameters dynamicSimulationParameters =
                        DynamicSimulationParametersCUtils.createDynamicSimulationParameters(parametersPtr);
                // the models are built first, so that the sets derived for them are known
                dynamicMapping.get(network, reportNode);
                // and what a model given after its parameters were written had added to value it is
                // said on the report the run keeps, once, from the sets get() derived
                ParameterCompletionReports.report(reportNode, dynamicMapping.getCompletions());
                dynamicMapping.getMappingParameters().ifPresent(mappingParameters -> {
                    DynawoSimulationParameters runParameters = dynamicMapping.getRunParameters();
                    // detached from a run before it: these settings belong to the mapping and are
                    // reused, but an extension holds to one extendable, so the same mapping run
                    // again, a value changed between runs as in a sweep, would fail to attach them
                    runParameters.setExtendable(null);
                    // the mapping's run parameters carry its network/solver settings; the study's provider
                    // parameters are merged on top so a value set from Python still reaches the jobs
                    // (update only touches the keys given, leaving the mapping's other settings intact)
                    if (!providerParameters.isEmpty()) {
                        runParameters.update(providerParameters);
                    }
                    dynamicSimulationParameters.addExtension(DynawoSimulationParameters.class, runParameters);
                });
                // a typed criteria model, when given, is set on the Dynawo parameters, after the mapping's
                // own run parameters have been attached above, so it reaches whichever extension the run
                // ends up with; it takes precedence over any criteria.file, and a null handle leaves the
                // criteria untouched.
                PythonCriteria criteria = ObjectHandles.getGlobal().get(criteriaHandle);
                if (criteria != null) {
                    dynamicSimulationParameters.getExtension(DynawoSimulationParameters.class)
                            .setCriteria(criteria.getCollection());
                }
                DynamicSimulationResult result = dynamicContext.run(network,
                        dynamicMapping,
                        eventModelsSupplier,
                        outputVariablesSupplier,
                        dynamicSimulationParameters,
                        reportNode);
                logger().info("Dynamic simulation ran successfully in java");
                return ObjectHandles.getGlobal().create(result);
            }
        });
    }

    @CEntryPoint(name = "updateDynamicMappings")
    public static void updateDynamicMappings(IsolateThread thread, ObjectHandle dynamicMappingHandle,
                                             CCharPointer categoryNamePtr,
                                             DataframeArrayPointer mappingDataframePtr,
                                             int strict,
                                             ExceptionHandlerPointer exceptionHandlerPtr) {
        doCatch(exceptionHandlerPtr, new Runnable() {
            @Override
            public void run() {
                PythonDynamicModelsSupplier supplier = ObjectHandles.getGlobal().get(dynamicMappingHandle);
                // below zero the study said nothing and the configuration decides
                supplier.setStrict(strict < 0 ? null : strict > 0);
                addMappings(dynamicMappingHandle, categoryNamePtr, mappingDataframePtr,
                        PythonDynamicModelsSupplier.Mode.KEEP_LAST);

            }
        });
    }

    @CEntryPoint(name = "addDynamicMappings")
    public static void addDynamicMappings(IsolateThread thread, ObjectHandle dynamicMappingHandle,
                                          CCharPointer categoryNamePtr,
                                          DataframeArrayPointer mappingDataframePtr,
                                          ExceptionHandlerPointer exceptionHandlerPtr) {
        doCatch(exceptionHandlerPtr, new Runnable() {
            @Override
            public void run() {
                addMappings(dynamicMappingHandle, categoryNamePtr, mappingDataframePtr,
                        PythonDynamicModelsSupplier.Mode.KEEP_FIRST);
            }
        });
    }

    // additional models set on the mapping, so they are registered when the mapping resolves — at
    // get_models as well as at a run (the run calls the mapping's get(), which resolves the recipes)
    @CEntryPoint(name = "addMappingAdditionalModels")
    public static void addMappingAdditionalModels(IsolateThread thread, ObjectHandle dynamicMappingHandle,
                                                  DataframePointer additionalModelsDataframePtr,
                                                  ExceptionHandlerPointer exceptionHandlerPtr) {
        doCatch(exceptionHandlerPtr, new Runnable() {
            @Override
            public void run() {
                PythonDynamicModelsSupplier dynamicMapping = ObjectHandles.getGlobal().get(dynamicMappingHandle);
                UpdatingDataframe additionalModelsDataframe = createDataframe(additionalModelsDataframePtr);
                dynamicMapping.getOrCreateMappingParameters().setAdditionalModels(
                        DynamicSimulationParametersCUtils.readAdditionalModels(additionalModelsDataframe));
            }
        });
    }

    private static void addMappings(ObjectHandle dynamicMappingHandle, CCharPointer categoryNamePtr,
                                    DataframeArrayPointer mappingDataframePtr, PythonDynamicModelsSupplier.Mode mode) {
        String categoryName = CTypeUtil.toString(categoryNamePtr);
        PythonDynamicModelsSupplier dynamicMapping = ObjectHandles.getGlobal().get(dynamicMappingHandle);
        List<UpdatingDataframe> mappingDataframes = new ArrayList<>();
        for (int i = 0; i < mappingDataframePtr.getDataframesCount(); i++) {
            mappingDataframes.add(createDataframe(mappingDataframePtr.getDataframes().addressOf(i)));
        }
        dynamicMapping.setDefaultMode(mode);
        try {
            DynamicMappingHandler.addElements(categoryName, dynamicMapping, mappingDataframes);
        } finally {
            dynamicMapping.setDefaultMode(PythonDynamicModelsSupplier.Mode.KEEP_FIRST);
        }
    }

    @CEntryPoint(name = "getDynamicMappingsMetaData")
    public static DataframesMetadataPointer getDynamicMappingsMetaData(IsolateThread thread,
                                                                       CCharPointer categoryNamePtr,
                                                                       ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public DataframesMetadataPointer get() {
                String categoryName = CTypeUtil.toString(categoryNamePtr);
                List<List<SeriesMetadata>> metadata = DynamicMappingHandler.getMetadata(categoryName);
                DataframeMetadataPointer dataframeMetadataArray = UnmanagedMemory.calloc(metadata.size() * SizeOf.get(DataframeMetadataPointer.class));
                int i = 0;
                for (List<SeriesMetadata> dataframeMetadata : metadata) {
                    CTypeUtil.createSeriesMetadata(dataframeMetadata, dataframeMetadataArray.addressOf(i));
                    i++;
                }
                DataframesMetadataPointer res = UnmanagedMemory.calloc(SizeOf.get(DataframesMetadataPointer.class));
                res.setDataframesMetadata(dataframeMetadataArray);
                res.setDataframesCount(metadata.size());
                return res;
            }
        });
    }

    @CEntryPoint(name = "createCriteria")
    public static ObjectHandle createCriteria(IsolateThread thread, ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, () -> ObjectHandles.getGlobal().create(new PythonCriteria()));
    }

    @CEntryPoint(name = "addCriteria")
    public static void addCriteria(IsolateThread thread, ObjectHandle criteriaHandle,
                                   DataframeArrayPointer criteriaDataframePtr,
                                   ExceptionHandlerPointer exceptionHandlerPtr) {
        doCatch(exceptionHandlerPtr, new Runnable() {
            @Override
            public void run() {
                PythonCriteria criteria = ObjectHandles.getGlobal().get(criteriaHandle);
                List<UpdatingDataframe> criteriaDataframes = new ArrayList<>();
                for (int i = 0; i < criteriaDataframePtr.getDataframesCount(); i++) {
                    criteriaDataframes.add(createDataframe(criteriaDataframePtr.getDataframes().addressOf(i)));
                }
                CriteriaDataframeAdder.addElements(criteria, criteriaDataframes);
            }
        });
    }

    @CEntryPoint(name = "getCriteriaMetaData")
    public static DataframesMetadataPointer getCriteriaMetaData(IsolateThread thread,
                                                                ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public DataframesMetadataPointer get() {
                List<List<SeriesMetadata>> metadata = CriteriaDataframeAdder.getMetadata();
                DataframeMetadataPointer dataframeMetadataArray = UnmanagedMemory.calloc(
                        metadata.size() * SizeOf.get(DataframeMetadataPointer.class));
                int i = 0;
                for (List<SeriesMetadata> dataframeMetadata : metadata) {
                    CTypeUtil.createSeriesMetadata(dataframeMetadata, dataframeMetadataArray.addressOf(i));
                    i++;
                }
                DataframesMetadataPointer res = UnmanagedMemory.calloc(SizeOf.get(DataframesMetadataPointer.class));
                res.setDataframesMetadata(dataframeMetadataArray);
                res.setDataframesCount(metadata.size());
                return res;
            }
        });
    }

    @CEntryPoint(name = "getCategories")
    public static ArrayPointer<CCharPointerPointer> getCategories(IsolateThread thread,
                                                                       ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, () ->
                Util.createCharPtrArray(List.copyOf(DynamicMappingHandler.getCategories())));
    }

    @CEntryPoint(name = "getCategoriesInformation")
    public static ArrayPointer<PyPowsyblApiHeader.SeriesPointer> getCategoriesInformation(IsolateThread thread,
                                                                                          ExceptionHandlerPointer exceptionHandlerPtr) {
        return Dataframes.createCDataframe(DynamicSimulationDataframeMappersUtils.categoriesDataFrameMapper(),
                DynamicMappingHandler.getDynamicMappingAdders());
    }

    @CEntryPoint(name = "getSupportedModels")
    public static ArrayPointer<CCharPointerPointer> getSupportedModels(IsolateThread thread,
                                                                       CCharPointer categoryNamePtr,
                                                                       ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<ArrayPointer<CCharPointerPointer>>() {
            @Override
            public ArrayPointer<CCharPointerPointer> get() throws IOException {
                String categoryName = CTypeUtil.toString(categoryNamePtr);
                return Util.createCharPtrArray(List.copyOf(
                        categoryName.isEmpty() ? DynamicMappingHandler.getAllSupportedModels()
                        : DynamicMappingHandler.getSupportedModels(categoryName)
                ));
            }
        });
    }

    @CEntryPoint(name = "getSupportedModelsInformation")
    public static ArrayPointer<PyPowsyblApiHeader.SeriesPointer> getSupportedModelsInformation(IsolateThread thread,
                                                                                               CCharPointer categoryNamePtr,
                                                                                               ExceptionHandlerPointer exceptionHandlerPtr) {
        String categoryName = CTypeUtil.toString(categoryNamePtr);
        return categoryName.isEmpty()
                ? Dataframes.createCDataframe(DynamicSimulationDataframeMappersUtils.allSupportedModelsDataFrameMapper(),
                    DynamicMappingHandler.getDynamicMappingAdders())
                : Dataframes.createCDataframe(DynamicSimulationDataframeMappersUtils.supportedModelsDataFrameMapper(),
                    DynamicMappingHandler.getSupportedModelsInformation(categoryName));
    }

    @CEntryPoint(name = "addEventMappings")
    public static void addEventMappings(IsolateThread thread, ObjectHandle eventMappingHandle,
                                        CCharPointer eventNamePtr,
                                        DataframePointer mappingDataframePtr,
                                        ExceptionHandlerPointer exceptionHandlerPtr) {
        doCatch(exceptionHandlerPtr, new Runnable() {
            @Override
            public void run() {
                PythonEventModelsSupplier eventMapping = ObjectHandles.getGlobal().get(eventMappingHandle);
                String eventName = CTypeUtil.toString(eventNamePtr);
                UpdatingDataframe mappingDataframe = createDataframe(mappingDataframePtr);
                EventMappingHandler.addElements(eventName, eventMapping, mappingDataframe);
            }
        });
    }

    @CEntryPoint(name = "getEventMappingsMetaData")
    public static DataframeMetadataPointer getEventMappingsMetaData(IsolateThread thread,
                                                                    CCharPointer eventNamePtr,
                                                                    ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<>() {
            @Override
            public DataframeMetadataPointer get() {
                String eventName = CTypeUtil.toString(eventNamePtr);
                return CTypeUtil.createSeriesMetadata(EventMappingHandler.getMetadata(eventName));
            }
        });
    }

    @CEntryPoint(name = "getEventsInformation")
    public static ArrayPointer<PyPowsyblApiHeader.SeriesPointer> getEventsInformation(IsolateThread thread,
                                                                                      ExceptionHandlerPointer exceptionHandlerPtr) {
        return Dataframes.createCDataframe(DynamicSimulationDataframeMappersUtils.eventInformationDataFrameMapper(),
                EventMappingHandler.getEventMappingAdders());
    }

    @CEntryPoint(name = "addOutputVariables")
    public static void addOutputVariables(IsolateThread thread,
                                          ObjectHandle outputVariablesHandle,
                                          CCharPointer dynamicIdPtr,
                                          CCharPointerPointer variablesPtrPtr,
                                          int variableCount,
                                          OutputVariableType variableType,
                                          ExceptionHandlerPointer exceptionHandlerPtr) {
        doCatch(exceptionHandlerPtr, new Runnable() {
            @Override
            public void run() {
                String dynamicId = CTypeUtil.toString(dynamicIdPtr);
                List<String> variables = toStringList(variablesPtrPtr, variableCount);
                PythonOutputVariablesSupplier outputVariablesSupplier = ObjectHandles.getGlobal().get(outputVariablesHandle);
                outputVariablesSupplier.addOutputVariables(dynamicId, variables, convert(variableType));
            }
        });
    }

    @CEntryPoint(name = "getDynamicSimulationResultsStatus")
    public static DynamicSimulationStatus getDynamicSimulationResultsStatus(IsolateThread thread,
             ObjectHandle resultsHandle,
             ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new Supplier<DynamicSimulationStatus>() {
            @Override
            public DynamicSimulationStatus get() {
                DynamicSimulationResult simulationResult = ObjectHandles.getGlobal().get(resultsHandle);
                return convert(simulationResult.getStatus());
            }
        });
    }

    @CEntryPoint(name = "getDynamicSimulationResultsStatusText")
    public static CCharPointer getDynamicSimulationResultsStatusText(IsolateThread thread,
                                                                 ObjectHandle resultsHandle,
                                                                 ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<CCharPointer>() {
            @Override
            public CCharPointer get() throws IOException {
                DynamicSimulationResult simulationResult = ObjectHandles.getGlobal().get(resultsHandle);
                return CTypeUtil.toCharPtr(simulationResult.getStatusText());
            }
        });
    }

    @CEntryPoint(name = "getDynamicCurves")
    public static ArrayPointer<SeriesPointer> getDynamicCurves(IsolateThread thread,
                                                               ObjectHandle resultHandle,
                                                               ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<ArrayPointer<SeriesPointer>>() {
            @Override
            public ArrayPointer<SeriesPointer> get() throws IOException {
                DynamicSimulationResult result = ObjectHandles.getGlobal().get(resultHandle);
                return TimeSeriesConverter.createCDataframe(result.getCurves().values().stream().toList());
            }
        });
    }

    @CEntryPoint(name = "getFinalStateValues")
    public static ArrayPointer<SeriesPointer> getFinalStateValues(IsolateThread thread, ObjectHandle resultHandle,
                                                                 ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<ArrayPointer<SeriesPointer>>() {
            @Override
            public ArrayPointer<SeriesPointer> get() throws IOException {
                DynamicSimulationResult result = ObjectHandles.getGlobal().get(resultHandle);
                return Dataframes.createCDataframe(DynamicSimulationDataframeMappersUtils.fsvDataFrameMapper(), result.getFinalStateValues());
            }
        });
    }

    @CEntryPoint(name = "getTimeline")
    public static ArrayPointer<SeriesPointer> getTimeline(IsolateThread thread,
                                                          ObjectHandle resultsHandle,
                                                          ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, new PointerProvider<ArrayPointer<SeriesPointer>>() {
            @Override
            public ArrayPointer<SeriesPointer> get() throws IOException {
                DynamicSimulationResult simulationResult = ObjectHandles.getGlobal().get(resultsHandle);
                return Dataframes.createCDataframe(DynamicSimulationDataframeMappersUtils.timelineEventDataFrameMapper(), simulationResult.getTimeLine());
            }
        });
    }

    @CEntryPoint(name = "getDynamicSimulationProviderParametersNames")
    public static PyPowsyblApiHeader.ArrayPointer<CCharPointerPointer> getDynamicSimulationProviderParametersNames(IsolateThread thread,
                                                                                                                   ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, () -> Util.createCharPtrArray(getSpecificParametersInfo().stream()
                .map(Parameter::getName)
                .collect(Collectors.toList())));
    }

    @CEntryPoint(name = "createDynamicSimulationProviderParametersSeriesArray")
    static PyPowsyblApiHeader.ArrayPointer<PyPowsyblApiHeader.SeriesPointer> createDynamicSimulationProviderParametersSeriesArray(IsolateThread thread,
                                                                                                                                  ExceptionHandlerPointer exceptionHandlerPtr) {
        return doCatch(exceptionHandlerPtr, () ->
                Dataframes.createCDataframe(SPECIFIC_PARAMETERS_MAPPER, getSpecificParametersInfo()));
    }
}
