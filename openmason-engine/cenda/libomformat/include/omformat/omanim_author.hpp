#pragma once

// .omanim authoring data (OpenMason #165): what the animation editor edits beyond the clip the runtime plays.
// USER DECISIONS 2026-11-25: authoring layers and control-rig keys live INSIDE the .omanim; the clip's joint tracks
// are always the BAKED result (layers composed, effectors solved); effector keys stay editable and every edit
// re-bakes. The runtime keeps the authoring data verbatim and ignores it.
//
// In the archive:
//   manifest.json "authoring": {
//       layers:    [{name, entry, mode: override|additive, weight, mute?, solo?, passthrough?, meshSpace?,
//                    mask?: [{joint | role, weight?, branch?}]}]     (layer 0 = the base layer; a layer's keys
//                   are its entry, an .omanim of its own; its keyed weight is that clip's curve "weight")
//       effectors: [{name, kind: twoBone | aim, upper?, mid?, end?, joints?: [...], axis?: [x,y,z],
//                    keys: [{time, pos: [x,y,z], rot?: [x,y,z] degrees, weight?, pole?: [x,y,z]}]}]
//   }
//   authoring/layer_<i>.omanim   one per layer
// A clip WITHOUT an authoring section opens as one base layer holding its tracks; a session that is still just
// that (one layer at full weight, no mask, no effectors) writes no authoring section and its tracks unbaked
// (lossless). Joint references are a joint id, a joint name or "role:<role>" (resolved by the host).
//
// Operations (apply; one JSON array, in order; the caller makes the batch atomic):
//   header        name?, fps?, duration?, loop?, modelRef? (string | null)
//   clip_layer    type? (BASE|OVERLAY), mask? [joint...] | null, fadeIn?, fadeOut?, priority?  (the clip's own
//                 runtime layer - how a game plays it - not an authoring layer)
//   key           layer?, joint, time, pos?, rot?, scale?, easing? (LINEAR|EASE_IN|EASE_OUT|EASE_IN_OUT|STEP);
//                 members left out keep the layer's current value at that time
//   remove_keys   layer?, joint?, times?: [t...] | from?, to?        (no joint = every track of the layer)
//   move_keys     layer?, joint?, times: [t...], offset              (seconds)
//   easing        layer?, joint?, times: [t...], easing
//   track         layer?, joint, remove: true
//   layer         name, mode?, weight?, mute?, solo?, passthrough?, meshSpace?, mask?, index?, rename?, remove?
//   layer_weight  layer, time, value | remove: true                   (the layer's keyable weight)
//   notify        name, time, duration?, payload?  |  index, remove   |  index, <members>
//   curve         name, keys: [{time, value, interp?, inTangent?, outTangent?}] | null
//   marker        name, time  |  index, remove
//   root_motion   joint, axes: [x, y, z, yaw]  |  null joint
//   additive      reference: none|frame|clip|character, time?, clip?, space?: local|mesh
//   effector      name, kind?, upper?, mid?, end?, joints?, axis?, rename?, remove?
//   effector_key  effector, time, pos?, rot?, weight?, pole?  |  remove: true
//   match         effector, time, to: ik | fk, layer?  (switch-and-match; host. fk keys the limb on `layer`, or -
//                 none given - on the topmost layer that holds the pose; refused when the layer cannot hold it)
//   bake          (re-bake now; host)
// Layer references: a name or an index ("base" = 0).

#include "omformat/omanim.hpp"

#include <string>
#include <string_view>
#include <vector>

namespace cenda::omformat::omanim::author {

enum class LayerMode : int { Override = 0, Additive = 1 };

struct MaskEntry {
    std::string joint;  // a joint reference (exactly one of joint / role)
    std::string role;
    float weight = 1.f;
    bool branch = true;
};

struct AuthorLayer {
    std::string name = "Base";
    LayerMode mode = LayerMode::Override;
    float weight = 1.f;
    bool mute = false;
    bool solo = false;
    bool passthrough = true;
    bool mesh_space = false;
    std::vector<MaskEntry> mask;
    Clip clip;  // its keys; curve "weight" = the keyed weight
};

struct EffectorKey {
    float time = 0.f;
    float pos[3]{0.f, 0.f, 0.f};  // model space
    bool has_rot = false;
    float rot[3]{0.f, 0.f, 0.f};  // model space Euler XYZ degrees (pins the end joint's rotation)
    float weight = 1.f;
    bool has_pole = false;
    float pole[3]{0.f, 0.f, 0.f};  // model space
};

struct Effector {
    std::string name;
    std::string kind = "twoBone";  // twoBone | aim
    std::string upper, mid, end;   // twoBone
    std::vector<std::string> joints;  // aim (parents first; the last points along `axis`)
    float axis[3]{0.f, 0.f, -1.f};
    std::vector<EffectorKey> keys;  // sorted by time
};

struct Session {
    Clip clip;  // header, notifies, curves, markers, root motion, additive, unknowns; tracks = the baked result
    std::vector<AuthorLayer> layers;  // >= 1
    std::vector<Effector> effectors;
};

// The session a clip holds (its authoring data, else one base layer of its tracks).
Session session_of(const Clip& clip);
// True when the session is just its base layer (written without authoring data, tracks unbaked).
bool trivial(const Session& s);
// The clip to write: authoring section + layer entries (none when trivial; the base tracks are the tracks then).
Clip clip_of(const Session& s);

// What applying needs from the rig.
class Host {
public:
    virtual ~Host() = default;
    // A joint reference resolved: id, name, bone flag. Throws format_error for an unknown joint.
    struct Joint {
        std::string id;
        std::string name;
        bool bone = false;
        std::string role;  // non-empty for a role-addressed track
    };
    virtual Joint resolve(const std::string& ref) = 0;
    // The layer's value of a joint at `time` (its track sampled; the rest pose where it has none).
    virtual Key sample(const Session& s, int layer, const Joint& joint, float time) = 0;
    // Switch-and-match (see the header comment); layer -1 = the host picks (fk).
    virtual void match(Session& s, int effector, float time, bool to_ik, int layer) = 0;
};

// Applies the operations in order; throws format_error "ops[<i>] (<op>): ..." on the first failure.
std::vector<std::string> apply(Session& s, std::string_view ops_json, Host& host);

// The editor's model as JSON: header, layers (settings, tracks: joint, name, role, bone, key times), effectors (keys),
// notifies, curves (name, key count), markers, rootMotion, additive.
std::string view(const Session& s);

// Index of a layer by name or index text ("base" = 0); -1 when none.
int find_layer(const Session& s, std::string_view ref);

}  // namespace cenda::omformat::omanim::author
