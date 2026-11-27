#include "../app/src/main/cpp/ffmedia.h"

#include <cassert>
#include <initializer_list>

using ffmedia::Face;
using ffmedia::Matcher;

static Face face(float x, std::initializer_list<float> embedding) {
    Face result;
    result.box = {x, 0.f, x + 0.2f, 0.2f};
    std::copy(embedding.begin(), embedding.end(), result.embedding.begin());
    return result;
}

int main() {
    const auto a = face(0.f, {1.f, 0.f, 0.f});
    const auto b = face(0.6f, {0.f, 1.f, 0.f});
    const auto stranger = face(0.3f, {0.f, 0.f, 1.f});
    const auto samePersonElsewhere = face(0.6f, {1.f, 0.f, 0.f});

    // Still-image instances can be assigned independently, including the same identity.
    assert(Matcher({{a}}).match({a}, true, false, 0.26f) == std::vector<int>{0});
    Matcher image({{a}, {samePersonElsewhere}});
    assert((image.match({samePersonElsewhere, a, stranger}, true, false, 0.26f)
            == std::vector<int>{1, 0, -1}));
    assert(ffmedia::sourceFor(1, {2, 0}, 3) == 0);
    assert(ffmedia::sourceFor(0, {2, 0}, 3) == 2);
    assert(ffmedia::sourceFor(-1, {2, 0}, 3) == -1);
    assert(ffmedia::sourceFor(1, {2, -1}, 3) == -1);
    assert(ffmedia::sourceFor(0, {2, 0}, 2) == -1);

    // Discovery finds someone missing from the first sample and recognizes an earlier face.
    assert((Matcher({{a}}).match({b, a}, false, false, 0.24f) == std::vector<int>{-1, 0}));
    Matcher video({{a}, {b}});
    assert(video.match({a}, false, true, 0.26f) == std::vector<int>{0});
    assert((video.match({b, samePersonElsewhere}, false, true, 0.26f)
            == std::vector<int>{1, 0}));
    for (int i = 0; i < 20; ++i) assert(video.match({}, false, true, 0.26f).empty());
    assert((video.match({stranger, a, b}, false, true, 0.26f) == std::vector<int>{-1, 0, 1}));

    // Near-tied claims for a single identity are both unsafe to route.
    assert((Matcher({{a}}).match({a, samePersonElsewhere}, false, false, 0.26f)
            == std::vector<int>{ffmedia::kAmbiguous, ffmedia::kAmbiguous}));

    // A smoothly moving face can survive a short appearance change; a random seek
    // and a dissimilar face in the same location get no such carryover.
    Matcher continuous({{a}});
    const auto changed = face(0.02f, {0.46f, 0.88792f});
    const auto intermediate = face(0.01f, {0.8f, 0.6f});
    assert(continuous.match({a}, false, true, 0.26f) == std::vector<int>{0});
    assert(continuous.match({intermediate}, false, true, 0.26f) == std::vector<int>{0});
    assert(continuous.match({changed}, false, true, 0.26f) == std::vector<int>{0});
    assert(continuous.match({changed}, false, false, 0.26f) == std::vector<int>{-1});
    assert(continuous.match({stranger}, false, true, 0.26f) == std::vector<int>{-1});

    // Alternate views identify the same person, even after a cut or absence.
    const auto alternate = face(0.01f, {0.7f, 0.714143f});
    Matcher multiView({{a, alternate}, {stranger}});
    const auto offAngle = face(0.8f, {0.29f, 0.957027f});
    const auto reentered = multiView.match({stranger, offAngle}, false, false,
                                          ffmedia::kVideoMatchDistance);
    assert((reentered == std::vector<int>{1, 0}));
    assert(ffmedia::sourceFor(reentered[0], {2, -1}, 3) == -1);
    assert(ffmedia::sourceFor(reentered[1], {2, -1}, 3) == 2);

    // A near miss can appear in the picker, but cannot register a duplicate identity.
    const auto uncertainPose = face(0.4f, {0.41f, 0.912086f});
    assert(Matcher({{a}}).match({uncertainPose}, false, false, 0.24f, true)
           == std::vector<int>{ffmedia::kAmbiguous});
    assert(Matcher({{a}}).match({stranger}, false, false, 0.24f, true)
           == std::vector<int>{ffmedia::kUnmatched});
    const auto invalid = face(0.4f, {});
    assert(Matcher({{a}}).match({invalid}, false, false, 0.24f, true)
           == std::vector<int>{ffmedia::kAmbiguous});

    // Reject flat, clipped, tiny and low-confidence detections at enrolment.
    std::array<float, 16> flat{};
    std::array<float, 16> sharp = {30, 30, 30, 30, 30, 120, 30, 30,
                                   30, 30, 120, 30, 30, 30, 30, 30};
    assert(ffmedia::pickerQuality(120, 1, 0.9f, 0.8f, ffmedia::sharpness<4>(flat)) == 0);
    assert(ffmedia::pickerQuality(24, 1, 0.9f, 0.8f, 12) == 0);
    assert(ffmedia::pickerQuality(120, 0.6f, 0.9f, 0.8f, 12) == 0);
    assert(ffmedia::pickerQuality(120, 1, 0.45f, 0.8f, 12) == 0);
    assert(ffmedia::pickerQuality(120, 1, 0.9f, 0.2f, 12) == 0);
    assert(ffmedia::pickerQuality(120, 1, 0.9f, 0.8f, ffmedia::sharpness<4>(sharp)) >
           ffmedia::pickerQuality(120, 1, 0.9f, 0.8f, 5));
}
