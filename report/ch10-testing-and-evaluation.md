# Chapter 10 - Testing and Evaluation

## 10.1 Test Strategy

Three levels, chosen so that each one tests something the others cannot reach
and so that the cheapest level carries the most cases.

**Table 10.1** The three levels of testing, and what each one answers

| Level | Runs on | Answers |
|---|---|---|
| Unit and integration | A desktop JVM, no device | Does the evaluator do what the specification says? |
| Replay | A desktop JVM, recorded video | Does the specification describe the movement? |
| Device | The target handset | Does the whole path work, at the rate it must? |

The split is possible because of one architectural property: the assessment
module has no Android dependency, so the layer carrying almost all of the
logic can be tested without an emulator, a device or a camera. 5.4 records how
that boundary is enforced.

The two questions in the middle column are genuinely different, and conflating
them is the easiest mistake available here. A suite that passes establishes
that a threshold is applied correctly. Whether the threshold is the right
number for the movement is a question only recorded video can answer, which
is what the replay level exists for.

**Determinism is what makes the bottom level possible.** NFR-A4 requires
identical landmark input to produce identical output. Every unit test depends
on it: each replays a fixed sequence and asserts an exact verdict. If the
evaluator carried any hidden state or any dependence on wall-clock time, the
suite could not be written at all.

## 10.2 Unit and Integration Tests

**85 tests in the assessment module and 9 in the client, 0 failures.**

**Table 10.2** What the unit and integration tests assert

| Concern | What is asserted |
|---|---|
| Specification loading | All eleven validation rules reject what they are meant to reject, and the failure is named rather than generic |
| Measurement vocabulary | Each function returns the expected value on known geometry, including when a landmark is missing |
| Segmentation | Hysteresis, phase resolution, the stillness band, the duration bounds, and the hold machine |
| Session behaviour | Counting, per-repetition and continuous evaluation, the per-repetition buffer emptying at each boundary |
| Trigger policy | Confidence floor, minimum gap, per-set cap, consecutive-cue limit, and priority between simultaneous faults |
| Tempo independence | The same movement performed fast and slow produces the same verdicts |

Traced to the requirements they serve:

**Table 10.3** Requirements traced to the tests that cover them

| Requirement | Tested by |
|---|---|
| FR-05 repetition segmentation and counting | Segmentation and session tests |
| FR-06 fault detection per the active specification | Session tests, over all three specifications |
| FR-08 the trigger policy governs speech and silence | Trigger policy tests |
| FR-19 exercises load from files with no code change | The suite runs all three specifications through one evaluator |
| FR-23 an invalid specification is rejected at load | Specification loading tests, one per rule |
| FR-24 every declared fault resolves to an audio asset | Specification loading tests |
| FR-25 every fault carries a provenance record | Specification loading tests |
| NFR-A4 identical input, identical output | Every test in the module, by construction |
| NFR-E3 validation failures are named | Specification loading tests |

**The tests load the real specification files rather than copies.** A
specification edited into a state the schema forbids fails this build. This is
the only arrangement under which the rules the format claims to enforce stay
true of the files that actually ship. It is why FR-25 is a property of the
format and not a convention.

**Tempo independence has a test of its own** because it is the failure mode
the segmenter is most prone to. A rule evaluated over a phase will see more
frames when the trainee moves slowly, and a confirmation window counted in
frames therefore behaves differently at different speeds. The test performs
the same movement at two tempos and asserts the same verdicts.

## 10.3 Device Testing

**Fourteen sets were performed on the target handset with the application
running**, across the development of the client. They establish the parts of
the system that no desktop test can reach: that the camera path sustains its
rate, that the pose engine runs on the GPU delegate under real conditions, and
that a confirmed fault reaches the speaker.

**Table 10.4** What device testing established, and what it could not

| Property | Result |
|---|---|
| Sustained frame rate during a set | 23.91 to 29.99 fps across the fourteen sets |
| Pose rate within a set | up to 0.993 of frames carrying a pose |
| Cues reaching audio | 18 cues spoken across the sets, with the path timed on each |
| Confirmation to audio dispatch, current build | 12 to 20 ms, median 16 |

**Every set in that range clears the NFR-P2 floor of 22 fps**, including the
slowest.

Device testing also found the two defects that only hardware could expose, and
both are recorded in the chapters that own them. A trigger-policy control that
silenced cues entirely while every other indicator looked healthy, which 6.5
describes. And a confidence floor carried unchanged across an engine change,
where the two engines report scores on different scales, which 6.5.1
describes. Neither is visible to a unit test, because in both cases the
evaluator was behaving exactly as specified.

## 10.4 Evaluation Method

Fault detection accuracy is evaluated against recorded video, scored by the
replay harness in 9.3, with the ground truth established two ways.

**By construction, for the scripted sets.** Each of the ten sets in 9.3.1 was
performed to one instruction, so the correct answer is known before the system
runs. Repetition counts were confirmed independently of the segmenter, by
reading excursions in the driver trace.

**By an independent expert, for the evaluation pass.** A certified trainer
has reviewed the recordings and confirmed that each performs the fault its
set is named for. The labelling pass below is a stronger thing and has not
been run; the protocol is blind in a specific sense:

- The trainer sees the video and nothing else. Not the system's output, not
  the author's labels, and not the fault set the system implements.
- They are asked what they observe, in their own words, rather than asked to
  confirm or reject a list.
- Their labels are compared against the system's afterwards, and agreement is
  reported as a figure rather than asserted.

**The order matters and cannot be repeated.** Once a labeller has seen the
system's output they cannot un-see it, and every judgement afterwards is
anchored. This is the one step in the whole evaluation that has exactly one
attempt, which is why the protocol fixes it in writing before any footage is
sent.

The metrics are those in 3.4.3: precision per fault against NFR-A1's 0.90,
recall per fault against NFR-A2's 0.70, and repetition count error against
NFR-A3's allowance of one. 9.4 reports all three against the scripted ground
truth. The trainer pass replaces the scripted labels with expert ones and
reports the same metrics again; what the trainers set is what is reported.

**Precision is weighted above recall deliberately**, and the gap between the
two targets is the requirement rather than an accident of two separately
chosen numbers. A missed fault costs one uncorrected repetition. A false cue
costs the trainee's confidence in the product, and 3.4.3 records that as the
most damaging failure this system has.

## 10.5 Limits of the Current Testing

**A green suite proves consistency with the specification, not correctness of
it.** Every unit test asserts that the evaluator applies a declared threshold
as declared. None of them can say whether that threshold is the right number
for a human movement, and no arrangement of unit tests could. That question
belongs entirely to the recorded sets and to the trainer pass.

**The test fixtures are synthetic.** They are generated landmark sequences
with known geometry, so they exercise the logic cleanly and they do not
contain the thing real data contains: noise correlated with the movement
itself. A landmark is noisiest exactly where the body occludes it, which is at
the bottom of a squat, in the phase where the depth rule is evaluated. The
fixtures cannot reproduce that, and the recorded sets are the only place it
appears.

**The evaluation set is one subject in one session.** 9.5 states this in full.
The consequence for this chapter is that no figure in 9.4 supports a claim
about a second person, and nothing in the testing method would reveal a
threshold that happens to suit this performer's proportions.

**One requirement has no test that closes it.** NFR-P1 is bounded below
rather than measured, because the platform path from the play call to the
first audible sample is not instrumented. It is stated here rather than left
to be discovered. NFR-P4 was closed by the ten-minute run in 9.4.1,
which also gives NFR-A5 its evidence; that run carries a caveat of its own
about the subject, stated where the figures are.

**The server has no tests because it has no implementation.** FR-16 to FR-18
describe synchronisation behaviour whose contract is fixed and whose code does
not exist in this phase. 11.3 reports that against the objective it serves
rather than counting it as passing.

**Device testing is a sample rather than a regime.** Fourteen sets across the
development of the client is enough to establish that the path works and to
find two defects that unit tests could not. It is not a stability programme,
and no claim is made about behaviour over repeated sessions, across reboots,
or on a device other than the one in 4.1.
