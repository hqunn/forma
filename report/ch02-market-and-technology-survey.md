# Chapter 2 - Market and Technology Survey

## 2.1 Survey Method

Five commercial products were surveyed from August to September 2026. A product
qualified on three conditions.

First, it claims to observe a user performing an exercise and give feedback on
how the movement was performed. Products that only count repetitions, only
prescribe a programme, or only track heart rate were excluded.

Second, it addresses general strength or bodyweight training. Products confined
to a single specialised discipline were excluded, because their movement
vocabulary and their failure modes do not compare.

Third, enough is published about it to place it on every criterion below. A
product described only in summary would enter the comparison with blank cells,
and a table with blanks invites conclusions it cannot support.

### 2.1.1 Comparison criteria

**Table 2.1** The criteria every product and system in this chapter is compared against

| Criterion | Why it is compared |
|---|---|
| Hardware required | Decides the addressable market and the setup friction |
| Inference location | Drives latency, privacy exposure and running cost |
| Feedback timing | During the repetition, after the set, or later |
| Feedback modality | Whether the user must look at a screen |
| Explains the cause | Whether the product says why, not only what |
| Published evaluation | Whether an accuracy claim can be checked |

The survey establishes what each product claims and is reported to do. It does
not establish how well any of them works, and 2.4 explains why no survey could.

## 2.2 Commercial Products

**Table 2.2** Five commercial products, and what each asks of the user

| Product | Hardware and inference | Feedback | Coverage | Price |
|---|---|---|---|---|
| Kemtai | Webcam in a browser, on the device | Real-time, audio and visual | 2,000+ rehab and fitness movements | Not published |
| Zing Coach | Phone camera, cloud | Real-time, visual prompts | Bodyweight and machines | About $18.99 per month |
| AiKYNETIX | Phone camera, cloud | Real-time, visual overlay | Barbell lifts | $12.99 per month |
| FormCheck AI | Phone camera, cloud | About 15 s after the set, visual | 30+ movements | About $12 to $13 per month |
| SensAI | Phone camera, cloud | Asynchronous, conversational text | Programme-dependent | $6.99 per month |

Prices are as listed at the survey date and are not independently verified. The
cloud product rows draw on a published category review (SensAI product review,
2026), which is itself published by one of the products listed.

Three patterns are worth drawing out.

Four of the five run inference in the cloud. That places a network round trip
inside the feedback path, and it gives the vendor a cost for every repetition
observed, which has to be recovered from the subscription.

Only one of the five speaks. The other four require the user to watch a screen
while training, which is not possible at the bottom of a squat. The one that
does speak needs a webcam and a browser, not a phone.

Two of the five have left the category without saying so. FormCheck AI returns
its analysis about fifteen seconds after the set and SensAI is asynchronous
altogether, so neither can change the repetition it is describing. At that
point form feedback has stopped being coaching and become review.

## 2.3 Research Background

### 2.3.1 Pose estimation on a mobile device

**Table 2.3** Mobile pose estimation engines, with the figures their authors publish

| Engine | Keypoints and topology | Published figure | Source |
|---|---|---|---|
| BlazePose | 33, including heel and foot index | Over 30 fps on a Pixel 2, single person | (Bazarevsky et al., 2020) |
| MediaPipe Pose Landmarker | 33, the shipped form of BlazePose | Lite, Full and Heavy variants | (Google MediaPipe documentation) |
| MoveNet | 17, COCO topology | Pixel 5 latency and COCO mAP published | (TensorFlow Blog, MoveNet, 2021) |
| RTMPose | Configurable, COCO topology by default | Real-time multi-person | (Jiang et al., 2023) |

These figures establish that real-time pose estimation on a commodity phone is
feasible. They do not establish performance on this project's device, and none
of them is carried forward as though it did.

These four differ in output topology as well as in speed. The COCO
seventeen-keypoint layout stops at the ankle, with no heel and no toe, while
BlazePose carries thirty-three points including both. Topology is the stronger
constraint where a fault depends on a landmark the layout does not contain,
because no amount of speed recovers a point the model never emits. Every fault
this project retains is expressible in either layout, so the choice between
them rests on measurement rather than on topology.

### 2.3.2 Movement assessment from skeleton sequences

**Table 2.4** Published systems that assess movement from skeleton sequences

| System | What it does | Data it used |
|---|---|---|
| Pose Trainer (Chen and Yang, Stanford, 2020) | Form recommendations for four exercises, evaluating geometric heuristics against learned classifiers | Over 100 videos the authors recorded, correct and deliberately incorrect |
| Fitness-AQA (Parmar, Gharat and Rhodin, ECCV 2022) | Self-supervised representations for workout form assessment | Real gym video, form errors annotated by professional trainers |
| EC3D (Zhao et al., ACCV 2022) | Graph convolutional network over pose sequences, identifying the mistake and proposing a correction | Squat, lunge and plank, correct and with deliberate mistakes, four subjects |

All three analyse recorded sequences after the fact. Offline, the whole
repetition is available before a verdict is required. Live, the system has to
commit while the movement is still happening and while a cue can still change
it. That is a different problem, and the accuracy figures these systems report
do not transfer to it.

### 2.3.3 Where the fault definitions come from

This project detects six technique faults across its three exercises: in the
squat, shallow depth and forward lean; in the push-up, the hips sagging and
the hips piking; in the plank, the hips sagging and the hips piking. Four
further faults were considered and excluded, each with its reason recorded in
the specification file that defines the exercise. Two are not observable in
the single camera view those exercises declare, one describes a deviation a
spoken cue cannot correct, and one has no source that defines it at all.

The set is adopted from published work rather than invented. The Functional
Movement Screen supplies scored observational criteria for the Deep Squat and
the Trunk Stability Push-up (Cook, Burton and Hoogenboom 2006), covering both
squat faults and push-up hip sag. The plank endurance test termination
criterion, consistent across protocols (Eimiller et al., 2025;
Rodríguez-Perea et al., 2025), names the two failure modes as the hips lowering and the pelvis
rising, which are the two plank faults. Those are test-termination rules
rather than a taxonomy annotated by trainers, which makes them weaker
evidence than the Functional Movement Screen, and the specification file
says so. Fitness-AQA
corroborates shallow depth in the squat as a fault that trainers annotate,
though its exercises are barbell movements, which this project does not cover.

Five of the six faults carry a cited criterion on this basis. The one that does
not is push-up piking, which rests on symmetry with a sourced fault, and that
is an argument rather than a citation. It is marked as unsourced in the
specification file rather than left to look settled.

What no source supplies is more consequential. Every source establishes that a
deviation is a fault and gives an observable condition for recognising it. None
gives the quantity at which the fault begins. The specifications therefore
record the taxonomy source and the threshold source as separate fields, and the
threshold field is empty throughout. No result from this project can therefore
be reported without stating how its threshold was derived.

## 2.4 Summary: What This Market Does Not Provide

Three questions were put to all five products.

**Table 2.5** Three questions put to all five products, and which of them answer each

| Question | Products that answer it |
|---|---|
| Does it explain the cause of a fault, rather than only naming it? | None during a set. One explains in prose, but only asynchronously |
| Does it publish how its accuracy was evaluated? | None |
| Does it state how a new exercise is added? | None. Coverage is announced as a number, never as a mechanism |

The second row matters most. Accuracy claims do appear in this market, but a
figure published without the procedure that produced it, without the data it
was measured on, and without any statement of who labelled that data cannot be
checked, compared, or reproduced. A buyer therefore has no basis for preferring
one product's accuracy over another's.

This is not an accusation. Publishing an evaluation earns a vendor nothing
commercially and no regulation requires it. But it leaves a gap that a project
judged on method rather than on sales is in an unusual position to close.

### 2.4.1 Language

None of the five surveyed products offers coaching in Vietnamese. Two smaller
applications list Vietnamese among their supported languages. A store listing
describes the interface and the store page, not the spoken coaching, and the
spoken cues those applications demonstrate are in English.

The finding this chapter records is therefore the narrow one: no product was
found that delivers spoken coaching cues in Vietnamese during a set. For a
product whose feedback path is audio by design, and whose users train in
Vietnamese, that is not a cosmetic difference.

### 2.4.2 Where FORMA sits

Four properties, each of which separates this project from all five products
above. No product in the survey offers them as a set.

**A phone the user already owns, and the video never leaves it.** One product
of the five keeps inference on the device, and it needs a webcam and a desktop
browser. The other four send the user's video to a server, which puts a network
round trip inside the feedback path and a cost per repetition behind the
subscription. FORMA requires no account, no upload and no network.

**It speaks while the user is still moving.** A person at the bottom of a squat
cannot read a screen, so four of the five are delivering their correction to
someone who is not looking at it. Two of those four arrive after the set has
finished, which is too late to change the repetition being described. Audio is
not an enhancement of the interface here; during a set it is the interface.

**It is built to coach in Vietnamese.** No surveyed product does. Every spoken
line is a pre-authored asset keyed by language in the specification files, so a
Vietnamese cue set is a content addition and not a change to the feedback path.
The bank currently holds English only, and nothing beyond that is claimed here.

**A new exercise is a specification file, not a release.** Three exercises run
through one evaluator with no exercise-specific code anywhere in it, which is a
passing test rather than a claim. No product in the survey states how an
exercise is added at all; coverage is announced as a number and the mechanism
behind it is never described.

Underneath these four is a quieter property: they can be checked. Every cue
traces to a published fault taxonomy, and every figure this project reports
names the procedure that produced it.
