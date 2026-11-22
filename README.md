# FORMA

An AI-powered personal training system on mobile devices. The phone watches a
set, judges the movement on the device, and speaks a correction while the
trainee is still in the repetition.

Specialized Project, CO4029. Dinh Hoang Quan, 2352986. Supervised by
Assoc. Prof. Dr. Quan Thanh Tho, Faculty of Computer Science and Engineering,
Ho Chi Minh City University of Technology.

The report is `DACN_261_2352986_DinhHoangQuan.pdf`. Everything else here is what it describes.

## What is in here

| Path | What it is |
|---|---|
| `app/forma/` | The Android client, and `assessment/`, the evaluator it is built on |
| `app/benchmark/` | A separate application that measures the pose engine, and the runs it produced |
| `specs/` | One JSON file per exercise, plus the format and the sources behind the fault definitions |
| `ml/replay.py` | Replays a recorded video through the shipped model and the shipped rules |
| `report/` | The report: the chapters in Markdown, the LaTeX that builds them, and the figures |

Three exercises are implemented: the bodyweight squat, the push-up and the
front plank. Six faults between them. The evaluator holds no branch on any
exercise name; adding one is authoring a file.

## The part worth reading first

`app/forma/assessment/` is a plain Kotlin library with no Android dependency.
It takes a frame of named landmarks and a timestamp, and returns a phase, a
count and at most one cue. Everything that decides whether a repetition was
correct lives there, which is why it can be tested on a desktop JVM with no
emulator and no camera.

`specs/bodyweight-squat.json` is the other half. A fault in it declares what
to measure, what to compare it against, how many frames confirm it, and where
the definition came from. The last of those is not optional: the loader
rejects a fault without a provenance record.

## Building and running

**The client.** Android Studio, or from `app/forma/`:

```
./gradlew :app:assembleDebug
```

Needs an Android SDK with API 36. The APK installs on any device running
Android 8.0 or later; it was developed and measured on a Samsung Galaxy Z
Flip5.

**The tests**, which need no device:

```
cd app/forma && ./gradlew test
```

85 tests in `assessment`, 9 in the client. They load the specification files
from `specs/` directly rather than copies, so a specification edited into a
state the format forbids fails this build.

**The benchmark**, from `app/benchmark/`:

```
./gradlew :app:assembleDebug
```

The model it needs is already at `app/benchmark/app/src/main/assets/`. The
three MediaPipe bundles it also compares are third-party weights and are not
included here; `app/benchmark/scripts/` fetches them. Its own output is in
`app/benchmark/results/`, and Appendix A of the report says which run decides
what.

**The replay harness**, which scores a recorded video with the same rules the
phone runs:

```
python ml/replay.py --video <file.mp4> --spec specs/bodyweight-squat.json
```

Needs `tensorflow` and `opencv-python`. The recordings it was run against are
not included: they are video of a person.

**The report**, from `report/latex/`:

```
python md2tex.py
pdflatex main.tex && bibtex main && pdflatex main.tex && pdflatex main.tex
```

The chapters are written in `report/*.md` and converted; the `.tex` files
under `report/latex/chapters/` are generated and should not be edited.

## What is deliberately not here

The recorded video and the trainee labels, because they are video of a
person. The project's own working notes. The MediaPipe model bundles, which
are third-party weights with their own licences.

Two photographs in the application are other people's work, used under their
licences and credited in `report/assets/ui/CREDITS.md` and on the screen that
shows them. `NOTICE.md` lists every piece of third-party material here and the
licence it travels under.
