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
constexpr int kMaxSamples = 3;
constexpr int kUnmatched = -1;
constexpr int kAmbiguous = -2;
constexpr float kVideoMatchDistance = 0.24f;
constexpr float kNoveltyDistance = 0.33f;

struct Face {
  std::array<float, 4> box{};
  std::array<float, kEmbeddingSize> embedding{};
};

using Gallery = std::vector<std::vector<Face>>;

inline float distance(const Face& a, const Face& b) {
  double dot = 0;
  for (int i = 0; i < kEmbeddingSize; ++i) dot += (double)a.embedding[i] * b.embedding[i];
  return (float)((1.0 - dot) * 0.5);
}

inline bool validEmbedding(const Face& face) {
  const float self = distance(face, face);
  return std::isfinite(self) && std::abs(self) < 0.05f;
}

inline float overlap(const Face& a, const Face& b) {
  const auto& x = a.box;
  const auto& y = b.box;
  const float w = std::max(0.f, std::min(x[2], y[2]) - std::max(x[0], y[0]));
  const float h = std::max(0.f, std::min(x[3], y[3]) - std::max(x[1], y[1]));
  const float total = (x[2] - x[0]) * (x[3] - x[1]) + (y[2] - y[0]) * (y[3] - y[1]) - w * h;
  return total > 0.f ? w * h / total : 0.f;
}

// Measure luma detail on an interior patch at a common scale across face sizes.
template <size_t Side>
float sharpness(const std::array<float, Side * Side>& patch) {
  float energy = 0.f;
  for (size_t y = 1; y + 1 < Side; ++y) {
    for (size_t x = 1; x + 1 < Side; ++x) {
      const size_t i = y * Side + x;
      const float detail = patch[i - 1] + patch[i + 1] + patch[i - Side] +
          patch[i + Side] - 4.f * patch[i];
      energy += detail * detail;
    }
  }
  return std::sqrt(energy / ((Side - 2) * (Side - 2)));
}

inline float pickerQuality(float pixels, float coverage, float detector, float landmarks,
                           float detail) {
  if (!std::isfinite(detail) || !std::isfinite(detector) || !std::isfinite(landmarks) ||
      pixels < 40.f || coverage < 0.88f || detector < 0.52f || landmarks < 0.38f ||
      detail < 3.f) return 0.f;
  return 0.55f * std::min(detail / 24.f, 1.f) +
      0.25f * std::min(pixels / 200.f, 1.f) +
      0.10f * std::clamp(detector, 0.f, 1.f) + 0.10f * std::clamp(landmarks, 0.f, 1.f);
}

class Matcher {
 public:
  explicit Matcher(Gallery people = {}) : people_(std::move(people)), recent_(people_.size()) {}

  // Each group is one identity. Multiple exemplars must never compete with one another.
  // Recent geometry only helps a plausible embedding during sequential playback/export.
  std::vector<int> match(const std::vector<Face>& faces, bool still, bool sequential,
                         float maxDistance, bool guardNovelty = false) {
    struct Claim { int face = kUnmatched; float best = 2.f; float second = 2.f; };
    std::vector<int> ids(faces.size(), kUnmatched);
    std::vector<Claim> claims(people_.size());
    for (size_t fi = 0; fi < faces.size(); ++fi) {
      if (!still && !validEmbedding(faces[fi])) {
        ids[fi] = kAmbiguous;
        continue;
      }
      int bestId = kUnmatched;
      float best = 2.f, second = 2.f, threshold = maxDistance, nearest = 2.f;
      for (size_t pi = 0; pi < people_.size(); ++pi) {
        float limit = maxDistance;
        float score = 2.f;
        if (still) {
          if (people_[pi].empty()) continue;
          score = 1.f - overlap(faces[fi], people_[pi].front());
          limit = 0.5f;
        } else {
          for (const auto& sample : people_[pi])
            score = std::min(score, distance(faces[fi], sample));
          nearest = std::min(nearest, score);
          const auto& recent = recent_[pi];
          if (sequential && recent.age <= 4 && overlap(faces[fi], recent.face) > 0.35f &&
              distance(faces[fi], recent.face) < 0.16f) {
            // A bounded allowance against the stored gallery prevents drift.
            limit += 0.04f;
          }
        }
        if (score < best) {
          second = best;
          best = score;
          bestId = (int)pi;
          threshold = limit;
        } else if (score < second) {
          second = score;
        }
      }
      if (bestId < 0 || best >= threshold) {
        if (guardNovelty && !still && nearest < kNoveltyDistance) ids[fi] = kAmbiguous;
        continue;
      }
      ids[fi] = kAmbiguous;
      if (second - best < 0.03f) continue;
      auto& claim = claims[bestId];
      if (best < claim.best) claim = {(int)fi, best, claim.best};
      else claim.second = std::min(claim.second, best);
    }

    if (sequential)
      for (auto& recent : recent_) recent.age = std::min(recent.age + 1, 5);

    for (size_t pi = 0; pi < claims.size(); ++pi) {
      const auto& claim = claims[pi];
      if (claim.face < 0 || claim.second - claim.best < 0.025f) continue;
      ids[claim.face] = (int)pi;
      if (sequential) recent_[pi] = {faces[claim.face], 0};
    }
    return ids;
  }

 private:
  struct Recent {
    Face face;
    int age = 5;
  };
  Gallery people_;
  std::vector<Recent> recent_;
};

inline int sourceFor(int identity, const std::vector<int>& slots, size_t slotCount) {
  if (identity < 0 || (size_t)identity >= slots.size()) return -1;
  const int slot = slots[identity];
  return slot >= 0 && (size_t)slot < slotCount ? slot : -1;
}

}  // namespace ffmedia
