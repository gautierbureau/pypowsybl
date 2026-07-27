/**
 * Copyright (c) 2020-2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.dynamic;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.dynamicsimulation.DynamicModel;
import com.powsybl.dynamicsimulation.DynamicModelsSupplier;
import com.powsybl.dynawo.DynawoSimulationConfig;
import com.powsybl.dynawo.DynawoSimulationParameters;
import com.powsybl.commons.PowsyblException;
import com.powsybl.dynawo.builders.ModelConfigsHandler;
import com.powsybl.dynawo.mappings.DynamicModelsMapping;
import com.powsybl.dynawo.mappings.DynamicModelsMappings;
import com.powsybl.dynawo.mappings.MappingConfig;
import com.powsybl.dynawo.mappings.MappingParameters;
import com.powsybl.dynawo.mappings.parameters.ModelDescriptionLookup;
import com.powsybl.dynawo.mappings.parameters.ParametersSetCompleter;
import com.powsybl.dynawo.desc.ModelDescription;
import com.powsybl.dynawo.models.AbstractBlackBoxModel;
import com.powsybl.dynawo.models.EquipmentBlackBoxModel;
import com.powsybl.dynawo.parameters.Parameter;
import com.powsybl.dynawo.parameters.ParametersSet;
import com.powsybl.iidm.network.Generator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.powsybl.iidm.network.Network;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * @author Nicolas Pierre {@literal <nicolas.pierre@artelys.com>}
 * @author Laurent Issertial {@literal <laurent.issertial at rte-france.com>}
 */
public class PythonDynamicModelsSupplier implements DynamicModelsSupplier {

    private static final Logger LOGGER = LoggerFactory.getLogger(PythonDynamicModelsSupplier.class);

    /**
     * What becomes of an equipment described twice.
     */
    public enum Mode {
        /**
         * The description already in place stands, so that a mapping can be completed with the
         * equipments it left out without disturbing those it covered.
         */
        KEEP_FIRST,
        /**
         * The new description stands, so that a mapping can be adjusted where it does not suit.
         */
        KEEP_LAST
    }

    private record Entry(BiFunction<Network, ReportNode, DynamicModel> modelFunction, Mode mode) {
    }

    private final List<Entry> entries = new ArrayList<>();

    /**
     * A mapping named to be applied later: chosen by name and settled with its settings, but not
     * against a network yet. It becomes models when {@link #get(Network, ReportNode)} is first
     * asked, which is where the network it needs comes.
     */
    private record Recipe(String name, MappingParameters parameters) {
    }

    private final List<Recipe> recipes = new ArrayList<>();

    /**
     * Whether the recipes have been turned into models yet. They are turned once: the models are
     * added as they resolve, so a second turn would stand up every one of them again and register
     * what it built into the catalog twice.
     */
    private boolean recipesResolved;

    /**
     * A parameter change waiting for the set it names to exist. A recipe writes its sets only once
     * it is applied to a network, so a value changed before that is held here and applied when the
     * recipe resolves, rather than refused on a set that is not written yet.
     */
    private record PendingParameterUpdate(String setId, String name, String value) {
    }

    private final List<PendingParameterUpdate> pendingParameterUpdates = new ArrayList<>();

    /**
     * Settings a mapping generated along with its models, the parameters of each model among them.
     * They travel with the models because they describe them: a set is written for one model of one
     * equipment and means nothing without it.
     */
    private DynawoSimulationParameters mappingParameters;

    /**
     * Mode the models added next are given. The adders building models from a dataframe call
     * {@link #addModel(BiFunction)} without knowing which of the two the caller asked for, so the
     * caller says it here beforehand.
     */
    private Mode defaultMode = Mode.KEEP_FIRST;

    /**
     * Where the parameters a model declares are read from, kept from the mapping that was applied
     * so that a model given to an equipment afterwards can be valued.
     */
    private ModelDescriptionLookup descriptions;

    /**
     * Sets derived for the models the study's own parameters do not value, rebuilt each time the
     * models are, so that reading them twice says the same thing.
     */
    private final List<ParameterCompletion> completions = new ArrayList<>();

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    /**
     * Whether a set that does not value the model it is given is completed or refused.
     * <p>
     * A deployment that must not see a parameter appear on its own says so in its configuration,
     * and a study overrides it call by call.
     */
    private static boolean isStrict(Boolean strict) {
        return strict != null ? strict : MappingConfig.load().isStrict();
    }

    /**
     * Names a mapping to be applied to whatever network the models are later asked for, with the
     * settings it takes but no network of its own. This is how a mapping is added the lazy way the
     * dataframe adders already are: the network comes at {@link #get(Network, ReportNode)}.
     */
    public void addMappingRecipe(String name, MappingParameters parameters) {
        recipes.add(new Recipe(name, parameters));
    }

    /**
     * Turns every recipe into models, once, against the network they are wanted for.
     * <p>
     * A recipe carries no network, so here is where its extensions are created, its models
     * resolved and built, and the sets it writes gathered into the settings a run reads. What it
     * built exists only in those settings and is stood up through the catalog, so the catalog is
     * told about it before the models are read, the ordering a native image forced on us once and
     * is kept here too.
     */
    private void resolveRecipes(Network network, ReportNode reportNode) {
        if (recipesResolved || recipes.isEmpty()) {
            return;
        }
        recipesResolved = true;
        Path homeDir = DynawoSimulationConfig.load().getHomeDir();
        ModelDescriptionLookup installed = ModelDescriptionLookup.fromModelDatabase(homeDir);
        // the settings the recipes fill in, the study's own if it already had some so a mapping
        // composes with parameters loaded or set by hand, a fresh set otherwise
        DynawoSimulationParameters recipeParameters = getMappingParameters().orElseGet(() -> {
            DynawoSimulationParameters fresh = new DynawoSimulationParameters();
            setMappingParameters(fresh);
            return fresh;
        });
        for (Recipe recipe : recipes) {
            DynamicModelsMapping mapping = DynamicModelsMappings.getInstance()
                    .create(recipe.name(), recipe.parameters());
            DynamicModelsSupplier models = DynamicModelsMappings.getInstance()
                    .apply(mapping, network, recipeParameters, installed, reportNode);
            registerBuiltModels(recipeParameters);
            models.get(network, reportNode).forEach(model -> addModel((n, r) -> model, Mode.KEEP_FIRST));
        }
        if (descriptions == null) {
            descriptions = installed;
        }
        // the sets exist now, so a change made against one before the recipe was applied lands
        applyPendingParameterUpdates();
    }

    /**
     * Changes one value in a set, or holds the change until the set exists.
     * <p>
     * A set is there to change once a mapping is applied or loaded from a file, and then the value
     * is changed at once. A set a recipe writes is not there until the recipe is applied to a
     * network, so a change named before that is held and made when it resolves, rather than
     * refused on a set that does not exist yet. A change naming a set nothing will ever write is
     * refused where that is settled: at once where no recipe is waiting to write it, at resolution
     * where one was and still did not.
     */
    public void updateParameterValue(String setId, String name, String value) {
        ParametersSet set = getOrCreateMappingParameters().getModelParameters().stream()
                .filter(s -> s.getId().equals(setId))
                .findFirst()
                .orElse(null);
        if (set != null) {
            applyParameterValue(set, name, value);
        } else if (!recipesResolved && !recipes.isEmpty()) {
            pendingParameterUpdates.add(new PendingParameterUpdate(setId, name, value));
        } else {
            throw new PowsyblException("Model parameter set " + setId + " not found");
        }
    }

    private static void applyParameterValue(ParametersSet set, String name, String value) {
        Parameter parameter = set.getParameters().get(name);
        if (parameter == null) {
            throw new PowsyblException("Parameter " + name + " not found in set " + set.getId());
        }
        // the type is the one the model declares, only the value is the study's to choose
        set.replaceParameter(name, parameter.type(), value);
    }

    private void applyPendingParameterUpdates() {
        List<PendingParameterUpdate> pending = List.copyOf(pendingParameterUpdates);
        pendingParameterUpdates.clear();
        pending.forEach(update -> updateParameterValue(update.setId(), update.name(), update.value()));
    }

    /**
     * Tells the catalog about the models a mapping built, so they can be stood up rather than
     * passed over for want of a builder. Applying the mapping is what fills these in.
     */
    private static void registerBuiltModels(DynawoSimulationParameters parameters) {
        if (!parameters.getAdditionalModelOverrides().isEmpty()) {
            ModelConfigsHandler.getInstance().overrideModels(parameters.getAdditionalModelOverrides());
        }
        if (!parameters.getAdditionalModels().isEmpty()) {
            ModelConfigsHandler.getInstance().addModels(parameters.getAdditionalModels());
        }
    }

    @Override
    public List<DynamicModel> get(Network network, ReportNode reportNode) {
        resolveRecipes(network, reportNode);
        ReportNode supplierReportNode = SupplierReport.createDynawoModelsSupplierReportNode(reportNode);
        Map<String, DynamicModel> describedEquipments = new LinkedHashMap<>();
        List<DynamicModel> others = new ArrayList<>();
        for (Entry entry : entries) {
            DynamicModel model = entry.modelFunction().apply(network, supplierReportNode);
            if (model == null) {
                continue;
            }
            describedEquipment(model).ifPresentOrElse(equipment -> {
                if (entry.mode() == Mode.KEEP_LAST) {
                    keepTheParametersOfWhatItReplaces(describedEquipments.put(equipment, model), model);
                } else {
                    describedEquipments.putIfAbsent(equipment, model);
                }
            }, () -> others.add(model));
        }
        List<DynamicModel> models = new ArrayList<>(describedEquipments.values());
        models.addAll(others);
        completions.clear();
        models.forEach(this::completeParameters);
        return models;
    }

    /**
     * Gives a model replacing another the set the one it replaces was valued by, where nothing was
     * said about parameters.
     * <p>
     * Describing an equipment names a set, and one that says nothing is given the equipment to
     * name it after. That is right for a description standing on its own and wrong for one
     * replacing another: the mapping wrote a set for the equipment under a name of its own, and
     * taking the default would quietly leave it behind, so the model would run on nothing while
     * the set that values it sat unused.
     * <p>
     * Only a description that named nothing is given one: a set named outright is left alone, even
     * where the study does not hold it, since that is something to be told about rather than
     * quietly overruled.
     */
    private void keepTheParametersOfWhatItReplaces(DynamicModel replaced, DynamicModel model) {
        if (mappingParameters == null
                || !(replaced instanceof EquipmentBlackBoxModel replacedModel)
                || !(model instanceof EquipmentBlackBoxModel newModel)
                || !(model instanceof AbstractBlackBoxModel valuedModel)) {
            return;
        }
        String named = newModel.getParameterSetId();
        // what a description naming no set is given, which is how one is known to have named none
        if (!newModel.getEquipment().getId().equals(named) || holdsSet(named)
                || !holdsSet(replacedModel.getParameterSetId())) {
            return;
        }
        valuedModel.setParameterSetId(replacedModel.getParameterSetId());
        LOGGER.info("{} says nothing of its parameters, keeping {} which valued the model it replaces",
                newModel.getEquipment().getId(), replacedModel.getParameterSetId());
    }

    private boolean holdsSet(String id) {
        return mappingParameters.getModelParameters().stream().anyMatch(s -> s.getId().equals(id));
    }

    /**
     * Derives, for a model given to an equipment after its parameters were written, a set valuing
     * it. The set the study holds is left as it is: what the model needs on top of it is kept
     * beside it, to be read before a run and handed to the simulation when it starts.
     */
    private void completeParameters(DynamicModel model) {
        if (descriptions == null || mappingParameters == null
                || !(model instanceof EquipmentBlackBoxModel equipmentModel)
                || !(model instanceof AbstractBlackBoxModel valuedModel)
                || !(equipmentModel.getEquipment() instanceof Generator equipment)) {
            return;
        }
        String baseId = baseSetId(equipmentModel.getParameterSetId(), equipmentModel.getLib());
        ParametersSet set = mappingParameters.getModelParameters().stream()
                .filter(s -> s.getId().equals(baseId))
                .findFirst()
                .orElse(null);
        if (set == null) {
            return;
        }
        ModelDescription description = descriptions.find(equipmentModel.getLib()).orElse(null);
        if (description == null) {
            return;
        }
        List<String> missing = ParametersSetCompleter.missingParameters(set, description);
        if (missing.isEmpty()) {
            return;
        }
        if (isStrict(strict)) {
            throw new PowsyblException("Parameter set " + set.getId() + " does not value model "
                    + equipmentModel.getLib() + " of " + equipment.getId() + ", it lacks " + missing);
        }
        String completedId = set.getId() + "_" + equipmentModel.getLib();
        ParametersSet completed = new ParametersSetCompleter()
                .complete(completedId, set, description, equipment, description.name().contains("Tfo"));
        List<Parameter> added = missing.stream()
                .map(name -> completed.getParameters().get(name))
                .filter(Objects::nonNull)
                .toList();
        completions.add(new ParameterCompletion(equipment.getId(), equipmentModel.getLib(), set, completed, added));
        valuedModel.setParameterSetId(completedId);
        LOGGER.info("Set {} did not value model {} of {}, {} derived from it holds {} more",
                set.getId(), equipmentModel.getLib(), equipment.getId(), completedId, added.size());
    }

    /**
     * The base a completed set was derived from: the model's set with the suffix completing adds
     * taken back off, where taking it off names a set that is really there.
     * <p>
     * Completing mutates the model to point at the set it derives, and a model is read more than
     * once, to gather the sets and then to build the dyd. So on the second read the model already
     * holds the completed set, and completing it again from there would grow the name a suffix at
     * a time and never settle on a model the completion cannot fully value. Completing from the
     * base each time settles it: the same completed set the first time and every time after.
     */
    private String baseSetId(String currentId, String lib) {
        String suffix = "_" + lib;
        if (currentId.endsWith(suffix)) {
            String stripped = currentId.substring(0, currentId.length() - suffix.length());
            boolean strippedExists = mappingParameters.getModelParameters().stream()
                    .anyMatch(s -> s.getId().equals(stripped));
            if (strippedExists) {
                return stripped;
            }
        }
        return currentId;
    }

    /**
     * What had to be added for the models given to equipments after their parameters were written.
     * Reading it says what a run would use before it is run.
     */
    public List<ParameterCompletion> getCompletions() {
        return List.copyOf(completions);
    }

    /**
     * The settings a simulation runs with: the ones the study holds, and the sets derived for the
     * models that its own do not value.
     */
    public DynawoSimulationParameters getRunParameters() {
        DynawoSimulationParameters parameters = getOrCreateMappingParameters();
        completions.forEach(completion -> parameters.addModelParameters(completion.completed()));
        return parameters;
    }

    /**
     * The equipment a model describes, when it describes one: a model standing on its own, an
     * automation system for instance, is never a second description of anything.
     */
    public static Optional<String> describedEquipment(DynamicModel model) {
        return model instanceof EquipmentBlackBoxModel equipmentModel
                ? Optional.of(equipmentModel.getEquipment().getId())
                : Optional.empty();
    }

    public void setDefaultMode(Mode defaultMode) {
        this.defaultMode = Objects.requireNonNull(defaultMode);
    }

    public void addModel(BiFunction<Network, ReportNode, DynamicModel> modelFunction) {
        addModel(modelFunction, defaultMode);
    }

    public void addModel(BiFunction<Network, ReportNode, DynamicModel> modelFunction, Mode mode) {
        entries.add(new Entry(modelFunction, Objects.requireNonNull(mode)));
    }

    /**
     * Follows the configuration when null, which is what a study that says nothing gets.
     */
    private Boolean strict;

    public void setStrict(Boolean strict) {
        this.strict = strict;
    }

    public void setDescriptions(ModelDescriptionLookup descriptions) {
        this.descriptions = descriptions;
    }

    public void setMappingParameters(DynawoSimulationParameters mappingParameters) {
        this.mappingParameters = mappingParameters;
    }

    public Optional<DynawoSimulationParameters> getMappingParameters() {
        return Optional.ofNullable(mappingParameters);
    }

    /**
     * The settings the simulation will run with, whether a mapping generated them, a study loaded
     * them from a file or the platform configuration declares them. They are read from the
     * configuration the first time they are asked for, so that a value can be looked at or changed
     * without a mapping having been applied.
     */
    public DynawoSimulationParameters getOrCreateMappingParameters() {
        if (mappingParameters == null) {
            mappingParameters = DynawoSimulationParameters.load();
        }
        return mappingParameters;
    }
}
