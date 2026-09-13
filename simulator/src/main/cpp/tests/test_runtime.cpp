// Tests for the neural runtime dynamics: propagation, thresholds, decay,
// refractory, reset, input injection, ablation, group readouts.

#include "core/runtime.h"

#include <memory>

#include "test_framework.h"

namespace {

std::shared_ptr<pocketfly::BrainData> chainBrain() {
    // 3 neurons, 2 groups. 0 -> 1 -> 2, unit weights.
    // Group 0: {0}, group 1: {1, 2}. Neuron 0 is a sensory input.
    auto b = std::make_shared<pocketfly::BrainData>();
    b->meta.neuronCount = 3;
    b->meta.edgeCount = 2;
    b->rowOffsets = {0, 1, 2, 2};
    b->targets = {1, 2};
    b->weights = {1.0f, 1.0f};
    b->groupIdx = {0, 1, 1};
    b->typeIdx = {0, 1, 1};
    b->regionIdx = {0, 0, 0};
    b->side = {pocketfly::kSideLeft, pocketfly::kSideMid, pocketfly::kSideMid};
    b->flags = {pocketfly::kNeuronFlagInput, 0, pocketfly::kNeuronFlagOutput};
    return b;
}

}  // namespace

PF_TEST(RuntimePropagatesAlongChain) {
    pocketfly::Runtime rt;
    rt.adoptBrain(chainBrain());
    rt.setInput(0, 1.0f);
    rt.step(1);
    // Step 1: neuron 0 spikes from drive; nothing downstream yet.
    PF_CHECK(rt.getActiveNeuronCount() == 1);
    PF_CHECK_NEAR(rt.getActivity(0), 1.0f, 1e-6);
    rt.step(1);
    // Step 2: neuron 1 receives the spike from 0. Neuron 0 is refractory.
    PF_CHECK_NEAR(rt.getActivity(1), 1.0f, 1e-6);
    PF_CHECK_NEAR(rt.getActivity(2), 0.0f, 1e-6);
    rt.step(1);
    // Step 3: neuron 2 fires.
    PF_CHECK_NEAR(rt.getActivity(2), 1.0f, 1e-6);
}

PF_TEST(RuntimeThresholdAndDecay) {
    pocketfly::Runtime rt;
    rt.adoptBrain(chainBrain());
    rt.setParams({0.8f, 1.0f, 1.0f, 0.0f, 0.85f, true, 0.0f});
    // Sub-threshold equilibrium: V converges to drive / (1 - decay) = 0.95,
    // below the spike threshold, so the neuron never fires.
    rt.setInput(1, 0.19f);
    for (int i = 0; i < 200; ++i) rt.step(1);
    PF_CHECK_NEAR(rt.getActivity(1), 0.0f, 1e-6);
    PF_CHECK_NEAR(rt.getPotential(1), 0.95f, 1e-4);
}

PF_TEST(RuntimeSubThresholdDriveDecays) {
    pocketfly::Runtime rt;
    rt.adoptBrain(chainBrain());
    rt.setInput(1, 0.5f);
    rt.step(1);
    PF_CHECK(rt.getPotential(1) > 0.0f);
    rt.clearInputs();
    for (int i = 0; i < 80; ++i) rt.step(1);
    PF_CHECK(rt.getPotential(1) < 1e-4f);
    PF_CHECK_NEAR(rt.getActivity(1), 0.0f, 1e-6);
}

PF_TEST(RuntimeResetClearsState) {
    pocketfly::Runtime rt;
    rt.adoptBrain(chainBrain());
    rt.setInput(0, 1.0f);
    rt.step(20);
    PF_CHECK(rt.getActivity(2) > 0.0f);
    PF_CHECK(rt.getStats().totalSteps == 20);
    rt.reset();
    PF_CHECK_NEAR(rt.getActivity(0), 0.0f, 1e-6);
    PF_CHECK_NEAR(rt.getActivity(1), 0.0f, 1e-6);
    PF_CHECK_NEAR(rt.getActivity(2), 0.0f, 1e-6);
    PF_CHECK(rt.getStats().totalSteps == 0);
    // Drive survives reset; a fresh step re-ignites the chain.
    rt.step(3);
    PF_CHECK(rt.getActivity(2) > 0.0f);
}

PF_TEST(RuntimeAblationStopsPropagation) {
    pocketfly::Runtime rt;
    rt.adoptBrain(chainBrain());
    rt.setNeuronEnabled(1, false);
    rt.setInput(0, 1.0f);
    rt.step(50);
    PF_CHECK_NEAR(rt.getActivity(2), 0.0f, 1e-6);
    PF_CHECK(rt.getStats().ablatedNeurons == 1);
    // Reversible.
    rt.setNeuronEnabled(1, true);
    rt.step(3);
    PF_CHECK(rt.getActivity(2) > 0.0f);
    rt.clearAblations();
    PF_CHECK(rt.getStats().ablatedNeurons == 0);
}

PF_TEST(RuntimeGroupAblationAndReadout) {
    pocketfly::Runtime rt;
    rt.adoptBrain(chainBrain());
    rt.setInput(0, 1.0f);
    rt.step(30);
    // Group 1 holds neurons 1 and 2; both have spiked recently.
    PF_CHECK(rt.getGroupMeanActivity(1) > 0.0f);
    PF_CHECK(rt.getGroupSize(1) == 2);
    rt.setGroupEnabled(1, false);
    PF_CHECK(rt.getGroupMeanActivity(1) == 0.0f);  // only enabled members count
    PF_CHECK(rt.getStats().ablatedNeurons == 2);
    rt.clearAblations();
    PF_CHECK(rt.getGroupMeanActivity(1) > 0.0f);
}

PF_TEST(RuntimeInputGroupMapping) {
    pocketfly::Runtime rt;
    rt.adoptBrain(chainBrain());
    rt.setParams({0.8f, 1.0f, 1.0f, 0.0f, 0.85f, true, 0.0f});
    const float two[2] = {0.3f, 0.0f};
    rt.setInputGroup(1, two, 2);
    rt.step(1);
    PF_CHECK_NEAR(rt.getPotential(1), 0.3f, 1e-6);
    PF_CHECK_NEAR(rt.getPotential(2), 0.0f, 1e-6);
    // Mapping a shorter array zeroes the remaining members' drive.
    rt.setInput(2, 0.4f);
    rt.step(1);
    PF_CHECK_NEAR(rt.getPotential(2), 0.4f, 1e-6);
    const float one[1] = {0.2f};
    rt.setInputGroup(1, one, 1);
    rt.step(1);
    PF_CHECK_NEAR(rt.getPotential(1), 0.8f * 0.54f + 0.2f, 1e-5);  // 0.632
    PF_CHECK_NEAR(rt.getPotential(2), 0.8f * 0.4f, 1e-6);          // 0.32: drive cleared
}

PF_TEST(RuntimeNeuronInfo) {
    pocketfly::Runtime rt;
    rt.adoptBrain(chainBrain());
    const pocketfly::NeuronInfo info = rt.getNeuronInfo(0);
    PF_CHECK(info.id == 0);
    PF_CHECK(info.group == 0);
    PF_CHECK(info.inDegree == 0);
    PF_CHECK(info.outDegree == 1);
    PF_CHECK((info.flags & pocketfly::kNeuronFlagInput) != 0);
    PF_CHECK(info.enabled);
    const pocketfly::NeuronInfo mid = rt.getNeuronInfo(1);
    PF_CHECK(mid.inDegree == 1 && mid.outDegree == 1);
}

PF_TEST(RuntimeNoiseIsDeterministic) {
    auto run = []() {
        pocketfly::Runtime rt;
        rt.adoptBrain(chainBrain());
        rt.setParams({0.8f, 1.0f, 1.0f, 0.05f, 0.85f, true, 0.0f});
        rt.setInput(0, 1.0f);
        rt.step(40);
        return rt.getActivity(2);
    };
    // Fresh runtimes share the fixed seed, so noise realizations must match.
    PF_CHECK_NEAR(run(), run(), 1e-6);
}

PF_TEST(RuntimeWeightsRequireGain) {
    pocketfly::Runtime rt;
    rt.adoptBrain(chainBrain());
    // Gain 0.3: each spike from neuron 0 delivers 0.3 every other step; the
    // two-step leak equilibrium 0.3 / (1 - 0.64) = 0.83 stays sub-threshold.
    rt.setParams({0.8f, 1.0f, 0.3f, 0.0f, 0.85f, true, 0.0f});
    rt.setInput(0, 1.0f);
    rt.step(200);
    PF_CHECK_NEAR(rt.getActivity(1), 0.0f, 1e-6);
    // Full gain lets the same input drive the chain.
    rt.setParams({0.8f, 1.0f, 1.0f, 0.0f, 0.85f, true, 0.0f});
    rt.step(3);
    PF_CHECK(rt.getActivity(1) > 0.0f);
}
