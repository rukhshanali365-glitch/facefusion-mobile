#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <utility>
#include <vector>

namespace ffmedia {

constexpr int kEmbeddingSize = 512;
constexpr int kRecordSize = 4 + kEmbeddingSize;
constexpr int kMaxPeople = 128;
constexpr int kUnmatched = -1;
constexpr int kAmbiguous = -2;

struct Face {
  std::array<float, 4> box{};
  std::array<float, kEmbeddingSize> embedding{};
};

inline float distance(const Face& a, const Face& b) {
  double dot = 0;
  for (int i = 0; i < kEmbeddingSize; ++i) dot += (double)a.embedding[i] * b.embedding[i];
  return (float)((1.0 - dot) * 0.5);
}

inline float overlap(const Face& a, const Face& b) {
  const auto& x = a.box;
  const auto& y = b.box;
  const float w = std::max(0.f, std::min(x[2], y[2]) - std::max(x[0], y[0]));
  const float h = std::max(0.f, std::min(x[3], y[3]) - std::max(x[1], y[1]));
  const float total = (x[2] - x[0]) * (x[3] - x[1]) + (y[2] - y[0]) * (y[3] - y[1]) - w * h;
  return total > 0.f ? w * h / total : 0.f;
}

class Matcher {
 public:
  explicit Matcher(std::vector<Face> people = {}) : people_(std::move(people)), recent_(people_.size()) {}

  // Match once per identity per frame. Uncertain matches never inherit a source.
  // Still images use position so two instances of the same person can be assigned separately.
  std::vector<int> match(const std::vector<Face>& faces, bool still, bool sequential,
                         float maxDistance) {
    std::vector<int> ids(faces.size(), kUnmatched);
    std::vector<float> scores(faces.size(), 2.f);
    std::vector<int> winners(people_.size(), kUnmatched);
    for (size_t fi = 0; fi < faces.size(); ++fi) {
      int bestId = kUnmatched;
      float best = 2.f, second = 2.f;
      for (size_t pi = 0; pi < people_.size(); ++pi) {
        float score;
        if (still) {
          score = 1.f - overlap(faces[fi], people_[pi]);
          if (score >= 0.5f) continue;
        } else {
          const float d = distance(faces[fi], people_[pi]);
          const auto& recent = recent_[pi];
          const bool continuous = sequential && recent.age <= 4 &&
              overlap(faces[fi], recent.face) > 0.35f &&
              distance(faces[fi], recent.face) < 0.16f;
          if (!std::isfinite(d) || d >= maxDistance + (continuous ? 0.04f : 0.f)) continue;
          score = d - (continuous ? 0.02f : 0.f);
        }
        if (score < best) {
          second = best;
          best = score;
          bestId = (int)pi;
        } else if (score < second) {
          second = score;
        }
      }
      if (bestId < 0) continue;
      ids[fi] = kAmbiguous;
      if (second - best < 0.025f) continue;
      scores[fi] = best;
      const int previous = winners[bestId];
      if (previous < 0 || best < scores[previous]) winners[bestId] = (int)fi;
    }

    if (sequential)
      for (auto& recent : recent_) recent.age = std::min(recent.age + 1, 5);

    for (size_t pi = 0; pi < winners.size(); ++pi) {
      const int fi = winners[pi];
      if (fi < 0) continue;
      ids[fi] = (int)pi;
      if (sequential) recent_[pi] = {faces[fi], 0};
    }
    return ids;
  }

 private:
  struct Recent {
    Face face;
    int age = 5;
  };
  std::vector<Face> people_;
  std::vector<Recent> recent_;
};

// A negative or out-of-range source means keep the original face.
inline int sourceFor(int identity, const std::vector<int>& slots, size_t slotCount) {
  if (identity < 0 || (size_t)identity >= slots.size()) return -1;
  const int slot = slots[identity];
  return slot >= 0 && (size_t)slot < slotCount ? slot : -1;
}

}  // namespace ffmedia
