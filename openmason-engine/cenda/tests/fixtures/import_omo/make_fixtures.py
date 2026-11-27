#!/usr/bin/env python3
"""Generates the ctest `import_omo` fixtures (OpenMason #173, writer half). Pure stdlib.

Run from anywhere: python3 make_fixtures.py   (writes next to this script; the output is committed).

rig.gltf         7-joint skeleton under a rotated, offset "Armature" node; the IBMs come from a DIFFERENT bind
                 pose (bind != rest); a uniformly scaled joint (Chest 1.5); a 6-distinct-joint vertex and a vertex
                 with a repeated joint; a skinned morph target with normal deltas; a mesh rigidly under a joint
                 (Hat under Head); a scaled mesh with a mesh child (Prop / PropChild); a mirrored mesh (Mirror);
                 two animations ("Move": LINEAR / STEP / CUBICSPLINE on joints, a scale track, a morph-weights
                 channel and a non-joint channel that are skipped; "Idle").
nonuniform.gltf  a joint with a non-uniform rest scale (warning; skinning stays exact).
fold.gltf        triangle pairs: folded 30 deg (2 faces), a concave dart (2 faces), a 0.5 deg fold (1 face at the
                 default 1 deg tolerance).
baked.gltf       a 3-joint skinned strip with a "Baked" clip sampled at 60 fps like a Blender export (121 LINEAR keys
                 per channel): Root translates along a line (rotation / scale constant), Arm turns 60 deg about Z then
                 holds, Wiggle swings about X along a sine (every key needed). The key reduction's large-ratio case.
textures.gltf    one triangle per material, each with an embedded PNG base colour texture: Flat (RGBA 40x20, one
                 colour), Gradient (RGB 37x23), Checker (grey 32x32, 1-pixel black / white cells), FlatGA (grey + alpha
                 30x30, one value), Fringe (RGBA 32x32: opaque white / transparent black columns), Small (RGB 8x8).
                 Imported with max_texture_size 16 (the downscale test).
"""

import base64
import json
import math
import os
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))

FLOAT, UBYTE, USHORT = 5126, 5121, 5123
FMT = {FLOAT: "f", UBYTE: "B", USHORT: "H"}
NCOMP = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


class Gltf:
    def __init__(self):
        self.doc = {"asset": {"version": "2.0", "generator": "OpenMason import_omo fixtures"}}
        self.bin = bytearray()

    def accessor(self, ctype, atype, values, minmax=False):
        n = NCOMP[atype]
        assert len(values) % n == 0
        raw = struct.pack("<%d%s" % (len(values), FMT[ctype]), *values)
        size = struct.calcsize(FMT[ctype]) * n
        stride = None
        if atype != "SCALAR" and size % 4:
            stride = (size + 3) // 4 * 4
            out = bytearray()
            for i in range(0, len(values), n):
                out += struct.pack("<%d%s" % (n, FMT[ctype]), *values[i:i + n]) + b"\0" * (stride - size)
            raw = bytes(out)
        while len(self.bin) % 4:
            self.bin.append(0)
        view = {"buffer": 0, "byteOffset": len(self.bin), "byteLength": len(raw)}
        if stride:
            view["byteStride"] = stride
        self.bin += raw
        self.doc.setdefault("bufferViews", []).append(view)
        a = {"bufferView": len(self.doc["bufferViews"]) - 1, "componentType": ctype, "count": len(values) // n,
             "type": atype}
        if minmax:
            a["min"] = [min(values[i::n]) for i in range(n)]
            a["max"] = [max(values[i::n]) for i in range(n)]
        self.doc.setdefault("accessors", []).append(a)
        return len(self.doc["accessors"]) - 1

    def add(self, key, obj):
        self.doc.setdefault(key, []).append(obj)
        return len(self.doc[key]) - 1

    def json(self):
        while len(self.bin) % 4:
            self.bin.append(0)
        self.doc["buffers"] = [{"byteLength": len(self.bin),
                                "uri": "data:application/octet-stream;base64," + base64.b64encode(bytes(self.bin)).decode()}]
        return json.dumps(self.doc, indent=1) + "\n"


def write(name, text):
    with open(os.path.join(HERE, name), "w", newline="\n") as f:
        f.write(text)


# ---- math (column-major 4x4 as flat lists, glTF order) ---------------------------------------------------------

def quat(axis, deg):
    x, y, z = axis
    l = math.sqrt(x * x + y * y + z * z)
    h = math.radians(deg) / 2
    s = math.sin(h) / l
    return [x * s, y * s, z * s, math.cos(h)]


def qmul(a, b):
    ax, ay, az, aw = a
    bx, by, bz, bw = b
    return [aw * bx + ax * bw + ay * bz - az * by, aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw, aw * bw - ax * bx - ay * by - az * bz]


def trs(t=(0, 0, 0), q=(0, 0, 0, 1), s=(1, 1, 1)):
    x, y, z, w = q
    r = [1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w),
         2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w),
         2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y)]
    m = [0.0] * 16
    for c in range(3):
        for rr in range(3):
            m[c * 4 + rr] = r[c * 3 + rr] * s[c]
    m[12], m[13], m[14], m[15] = t[0], t[1], t[2], 1.0
    return m


def mul(a, b):
    out = [0.0] * 16
    for c in range(4):
        for r in range(4):
            out[c * 4 + r] = sum(a[k * 4 + r] * b[c * 4 + k] for k in range(4))
    return out


def inverse(m):
    a = [[m[c * 4 + r] for c in range(4)] + [1.0 if i == r else 0.0 for i in range(4)] for r in range(4)]
    for col in range(4):
        piv = max(range(col, 4), key=lambda r: abs(a[r][col]))
        a[col], a[piv] = a[piv], a[col]
        p = a[col][col]
        a[col] = [v / p for v in a[col]]
        for r in range(4):
            if r != col:
                f = a[r][col]
                a[r] = [v - f * w for v, w in zip(a[r], a[col])]
    return [a[r][4 + c] for c in range(4) for r in range(4)]


def point(m, p):
    return [m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12], m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13],
            m[2] * p[0] + m[6] * p[1] + m[10] * p[2] + m[14]]


def rf(v):
    return [round(x, 7) for x in v]


# ---- rig.gltf --------------------------------------------------------------------------------------------------

def rig():
    g = Gltf()
    arm_t, arm_q = [0.5, 0.0, -0.25], quat((0, 1, 0), 30)
    # name, parent, t, q, s  (rest)
    joints = [
        ("Hips", None, [0, 1, 0], quat((1, 0, 0), 10), [1, 1, 1]),
        ("Spine", 0, [0, 0.6, 0.1], quat((0, 0, 1), 20), [1, 1, 1]),
        ("Chest", 1, [0, 0.5, 0], quat((0, 1, 0), 5), [1.5, 1.5, 1.5]),
        ("Neck", 2, [0, 0.3, 0], quat((0, 1, 0), -15), [1, 1, 1]),
        ("Head", 3, [0, 0.2, 0], quat((1, 1, 0), 25), [1, 1, 1]),
        ("ArmL", 0, [0.3, 0.4, 0], quat((0, 0, 1), -100), [1, 1, 1]),
        ("Tail", 0, [0, -0.2, -0.3], quat((1, 0, 0), 120), [1, 1, 1]),
    ]
    # The BIND pose differs: Spine straight, Head unrotated, ArmL at -90, Chest unscaled.
    bind_q = {"Spine": [0, 0, 0, 1], "Head": [0, 0, 0, 1], "ArmL": quat((0, 0, 1), -90)}
    bind_s = {"Chest": [1, 1, 1]}
    arm = trs(arm_t, arm_q)
    world_bind = []
    for name, parent, t, q, s in joints:
        local = trs(t, bind_q.get(name, q), bind_s.get(name, s))
        world_bind.append(mul(arm if parent is None else world_bind[parent], local))
    ibm = []
    for m in world_bind:
        ibm += rf(inverse(m))

    # Body: a 2 x 6 ladder authored in BIND space (in front of each joint's bind head), normals +Z.
    joint_of_row = [0, 1, 2, 3, 4, 5]
    pos, nrm = [], []
    for row in range(6):
        head = point(world_bind[joint_of_row[row]], [0, 0, 0])
        for col in range(2):
            pos += rf([head[0] - 0.1 + 0.2 * col, head[1] + 0.05, head[2] + 0.1])
            nrm += [0.0, 0.0, 1.0]
    tris = []
    for row in range(5):
        a = row * 2
        tris += [a, a + 1, a + 3, a, a + 3, a + 2]
    # Influences: 8 slots (JOINTS_0/1). v4: 6 distinct joints; v7: joint 3 twice (merged); others 1 or 2.
    j0, w0, j1, w1 = [], [], [], []
    for v in range(12):
        row = v // 2
        if v == 4:
            jj, ww = [0, 1, 2, 3, 4, 5, 0, 0], [0.3, 0.2, 0.15, 0.15, 0.1, 0.1, 0, 0]
        elif v == 7:
            jj, ww = [3, 4, 3, 0, 0, 0, 0, 0], [0.25, 0.5, 0.25, 0, 0, 0, 0, 0]
        elif v == 11:
            jj, ww = [5, 6, 0, 0, 0, 0, 0, 0], [0.6, 0.4, 0, 0, 0, 0, 0, 0]
        else:
            jj, ww = [joint_of_row[row], 0, 0, 0, 0, 0, 0, 0], [1, 0, 0, 0, 0, 0, 0, 0]
        j0 += jj[:4]
        w0 += ww[:4]
        j1 += jj[4:]
        w1 += ww[4:]
    morph = [0.0] * 36
    morph_n = [0.0] * 36
    for v in (4, 5, 6, 7):
        morph[v * 3 + 2] = 0.05
        morph_n[v * 3 + 1] = 0.2
    a_pos = g.accessor(FLOAT, "VEC3", pos, minmax=True)
    a_nrm = g.accessor(FLOAT, "VEC3", nrm)
    a_idx = g.accessor(USHORT, "SCALAR", tris)
    a_j0 = g.accessor(UBYTE, "VEC4", j0)
    a_w0 = g.accessor(FLOAT, "VEC4", w0)
    a_j1 = g.accessor(UBYTE, "VEC4", j1)
    a_w1 = g.accessor(FLOAT, "VEC4", w1)
    a_mp = g.accessor(FLOAT, "VEC3", morph, minmax=True)
    a_mn = g.accessor(FLOAT, "VEC3", morph_n)
    a_ibm = g.accessor(FLOAT, "MAT4", ibm)
    g.add("meshes", {"name": "BodyMesh", "weights": [0.25], "extras": {"targetNames": ["Breathe"]},
                     "primitives": [{"attributes": {"POSITION": a_pos, "NORMAL": a_nrm, "JOINTS_0": a_j0,
                                                    "WEIGHTS_0": a_w0, "JOINTS_1": a_j1, "WEIGHTS_1": a_w1},
                                     "indices": a_idx, "targets": [{"POSITION": a_mp, "NORMAL": a_mn}]}]})
    # Hat: a quad.
    hp = g.accessor(FLOAT, "VEC3", [-0.1, 0, -0.1, 0.1, 0, -0.1, 0.1, 0, 0.1, -0.1, 0, 0.1], minmax=True)
    hn = g.accessor(FLOAT, "VEC3", [0, 1, 0] * 4)
    hi = g.accessor(USHORT, "SCALAR", [0, 3, 2, 0, 2, 1])
    g.add("meshes", {"name": "HatMesh", "primitives": [{"attributes": {"POSITION": hp, "NORMAL": hn}, "indices": hi}]})
    # Prop: a flat-shaded cube (24 vertices, 12 triangles -> 6 quads).
    cp, cn, ci = [], [], []
    faces = [((1, 0, 0), (0, 1, 0)), ((-1, 0, 0), (0, 0, 1)), ((0, 1, 0), (0, 0, 1)), ((0, -1, 0), (1, 0, 0)),
             ((0, 0, 1), (1, 0, 0)), ((0, 0, -1), (0, 1, 0))]
    for n, u in faces:
        v = [n[1] * u[2] - n[2] * u[1], n[2] * u[0] - n[0] * u[2], n[0] * u[1] - n[1] * u[0]]
        base = len(cp) // 3
        for su, sv in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
            cp += [0.5 * (n[i] + su * u[i] + sv * v[i]) for i in range(3)]
            cn += list(n)
        ci += [base, base + 1, base + 2, base, base + 2, base + 3]
    pp = g.accessor(FLOAT, "VEC3", cp, minmax=True)
    pn = g.accessor(FLOAT, "VEC3", cn)
    pi = g.accessor(USHORT, "SCALAR", ci)
    g.add("meshes", {"name": "PropMesh", "primitives": [{"attributes": {"POSITION": pp, "NORMAL": pn}, "indices": pi}]})

    # Nodes.
    g.add("nodes", {"name": "Armature", "translation": arm_t, "rotation": arm_q, "children": [1, 8]})  # 0
    child_lists = {0: [2, 6, 7], 1: [3], 2: [4], 3: [5], 4: [9], 5: [], 6: []}
    for i, (name, parent, t, q, s) in enumerate(joints):  # nodes 1..7
        node = {"name": name, "translation": t, "rotation": rf(q)}
        if s != [1, 1, 1]:
            node["scale"] = s
        if child_lists[i]:
            node["children"] = child_lists[i]
        g.add("nodes", node)
    g.add("nodes", {"name": "Body", "mesh": 0, "skin": 0, "translation": [5, 5, 5]})  # 8 (transform ignored)
    g.add("nodes", {"name": "Hat", "mesh": 1, "translation": [0, 0.15, 0], "rotation": rf(quat((0, 0, 1), 10))})  # 9
    g.add("nodes", {"name": "Prop", "mesh": 2, "translation": [2, 0, 0], "scale": [2, 2, 2], "children": [11]})  # 10
    g.add("nodes", {"name": "PropChild", "mesh": 2, "translation": [0, 1, 0]})  # 11
    g.add("nodes", {"name": "Mirror", "mesh": 2, "translation": [-2, 0, 0], "scale": [-1, 1, 1]})  # 12
    g.add("skins", {"name": "Armature", "joints": [1, 2, 3, 4, 5, 6, 7], "inverseBindMatrices": a_ibm,
                    "skeleton": 0})
    g.doc["scenes"] = [{"nodes": [0, 10, 12]}]
    g.doc["scene"] = 0

    # Animations.
    def sampler(times, values, atype, interp):
        return {"input": g.accessor(FLOAT, "SCALAR", times, minmax=True), "output": g.accessor(FLOAT, atype, values),
                "interpolation": interp}

    spine_q = [rf(quat((0, 0, 1), 20)), rf(qmul(quat((1, 0, 0), 100), quat((0, 1, 0), 50))),
               rf(quat((0, 1, 1), -140))]
    head_v0, head_v1 = rf(quat((1, 1, 0), 25)), rf(quat((0, 1, 0), 80))
    head_out = [0.0, 0.3, 0.0, 0.0]
    head_in = [0.1, -0.2, 0.05, 0.0]
    samplers = [
        sampler([0, 1, 2], [0, 1, 0, 0, 1.2, 0.3, 0, 1, 0], "VEC3", "LINEAR"),               # 0 Hips T
        sampler([0, 1, 2], sum(spine_q, []), "VEC4", "LINEAR"),                             # 1 Spine R
        sampler([0, 2], [0, 0, 0, 0] + head_v0 + head_out + head_in + head_v1 + [0, 0, 0, 0], "VEC4",
                "CUBICSPLINE"),                                                             # 2 Head R
        sampler([0, 0.5, 1.5], rf(quat((1, 0, 0), 120)) + rf(quat((1, 0, 0), 60)) + rf(quat((0, 1, 0), 45)),
                "VEC4", "STEP"),                                                            # 3 Tail R
        sampler([0, 2], [1, 1, 1, 1.5, 1.5, 1.5], "VEC3", "LINEAR"),                         # 4 ArmL S
        sampler([0.5, 1.5], [0, 0.5, 0, 0.1, 0.6, -0.1], "VEC3", "LINEAR"),                  # 5 Chest T
        sampler([0.5, 1.5], rf(quat((0, 1, 0), 5)) + rf(quat((1, 0, 1), 70)), "VEC4", "LINEAR"),  # 6 Chest R
        sampler([0, 2], [0, 1], "SCALAR", "LINEAR"),                                        # 7 Body weights
        sampler([0, 2], [2, 0, 0, 3, 0, 0], "VEC3", "LINEAR"),                               # 8 Prop T
    ]
    g.add("animations", {"name": "Move", "samplers": samplers, "channels": [
        {"sampler": 0, "target": {"node": 1, "path": "translation"}},
        {"sampler": 1, "target": {"node": 2, "path": "rotation"}},
        {"sampler": 2, "target": {"node": 5, "path": "rotation"}},
        {"sampler": 3, "target": {"node": 7, "path": "rotation"}},
        {"sampler": 4, "target": {"node": 6, "path": "scale"}},
        {"sampler": 5, "target": {"node": 3, "path": "translation"}},
        {"sampler": 6, "target": {"node": 3, "path": "rotation"}},
        {"sampler": 7, "target": {"node": 8, "path": "weights"}},
        {"sampler": 8, "target": {"node": 10, "path": "translation"}}]})
    g.add("animations", {"name": "Idle", "samplers": [
        sampler([0, 1], rf(quat((0, 1, 0), -15)) + rf(quat((0, 1, 0), 15)), "VEC4", "LINEAR")],
        "channels": [{"sampler": 0, "target": {"node": 4, "path": "rotation"}}]})
    write("rig.gltf", g.json())


# ---- nonuniform.gltf -------------------------------------------------------------------------------------------

def nonuniform():
    g = Gltf()
    pos = [0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0]
    ap = g.accessor(FLOAT, "VEC3", pos, minmax=True)
    ai = g.accessor(USHORT, "SCALAR", [0, 1, 2, 0, 2, 3])
    aj = g.accessor(UBYTE, "VEC4", [0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0])
    aw = g.accessor(FLOAT, "VEC4", [1, 0, 0, 0] * 4)
    root = trs([0, 0, 0], [0, 0, 0, 1], [1, 2, 1])
    tip = mul(root, trs([0, 0.5, 0]))
    ibm = g.accessor(FLOAT, "MAT4", rf(inverse(root)) + rf(inverse(tip)))
    g.add("meshes", {"name": "Sheet", "primitives": [{"attributes": {"POSITION": ap, "JOINTS_0": aj, "WEIGHTS_0": aw},
                                                      "indices": ai}]})
    g.add("nodes", {"name": "Root", "scale": [1, 2, 1], "children": [1]})
    g.add("nodes", {"name": "Tip", "translation": [0, 0.5, 0]})
    g.add("nodes", {"name": "Sheet", "mesh": 0, "skin": 0})
    g.add("skins", {"joints": [0, 1], "inverseBindMatrices": ibm})
    g.doc["scenes"] = [{"nodes": [0, 2]}]
    g.doc["scene"] = 0
    write("nonuniform.gltf", g.json())


# ---- fold.gltf -------------------------------------------------------------------------------------------------

def fold():
    g = Gltf()
    c30, s30 = math.cos(math.radians(30)), math.sin(math.radians(30))
    c05, s05 = math.cos(math.radians(0.5)), math.sin(math.radians(0.5))
    pos = []
    # pair A (0..3): square folded 30 deg along its diagonal 0-2.
    pos += [0, 0, 0, 1, 0, 0, 1, 1, 0]
    pos += [0, 1, 0]
    # put vertex 3 out of plane: rotate the second triangle about the diagonal by 30 deg (approx: lift z).
    pos[9:12] = [0.5 - 0.5 * c30, 0.5 + 0.5 * c30, 0.5 * math.sqrt(2) * s30]
    # pair B (4..7): a concave dart.
    pos += [3, 0, 0, 4, 0, 0, 3.2, 0.2, 0, 3, 1, 0]
    # pair C (8..11): square folded 0.5 deg.
    pos += [6, 0, 0, 7, 0, 0, 7, 1, 0, 6.5 - 0.5 * c05, 0.5 + 0.5 * c05, 0.5 * math.sqrt(2) * s05]
    ap = g.accessor(FLOAT, "VEC3", rf(pos), minmax=True)
    ai = g.accessor(USHORT, "SCALAR", [0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7, 8, 9, 10, 8, 10, 11])
    g.add("meshes", {"name": "Fold", "primitives": [{"attributes": {"POSITION": ap}, "indices": ai}]})
    g.add("nodes", {"name": "Fold", "mesh": 0})
    g.doc["scenes"] = [{"nodes": [0]}]
    g.doc["scene"] = 0
    write("fold.gltf", g.json())


# ---- baked.gltf ------------------------------------------------------------------------------------------------

def baked():
    g = Gltf()
    pos, j0, w0 = [], [], []
    joint_of_row = [0, 1, 2, 2]
    for row in range(4):
        for col in range(2):
            pos += [-0.1 + 0.2 * col, float(row), 0.0]
            j0 += [joint_of_row[row], 0, 0, 0]
            w0 += [1.0, 0.0, 0.0, 0.0]
    tris = []
    for row in range(3):
        a = row * 2
        tris += [a, a + 1, a + 3, a, a + 3, a + 2]
    ap = g.accessor(FLOAT, "VEC3", pos, minmax=True)
    ai = g.accessor(USHORT, "SCALAR", tris)
    aj = g.accessor(UBYTE, "VEC4", j0)
    aw = g.accessor(FLOAT, "VEC4", w0)
    rest_world = [trs(), trs([0, 1, 0]), trs([0, 2, 0])]
    ibm = g.accessor(FLOAT, "MAT4", sum((rf(inverse(m)) for m in rest_world), []))
    g.add("meshes", {"name": "Strip", "primitives": [{"attributes": {"POSITION": ap, "JOINTS_0": aj, "WEIGHTS_0": aw},
                                                      "indices": ai}]})
    g.add("nodes", {"name": "Root", "children": [1]})                                  # 0
    g.add("nodes", {"name": "Arm", "translation": [0, 1, 0], "children": [2]})         # 1
    g.add("nodes", {"name": "Wiggle", "translation": [0, 1, 0]})                       # 2
    g.add("nodes", {"name": "Strip", "mesh": 0, "skin": 0})                            # 3
    g.add("skins", {"joints": [0, 1, 2], "inverseBindMatrices": ibm})
    g.doc["scenes"] = [{"nodes": [0, 3]}]
    g.doc["scene"] = 0

    frames = 121
    times = [i / 60.0 for i in range(frames)]
    t_in = g.accessor(FLOAT, "SCALAR", times, minmax=True)

    def sampler(values, atype):
        return {"input": t_in, "output": g.accessor(FLOAT, atype, values), "interpolation": "LINEAR"}

    root_t, ident_q, ones, arm_t, arm_q, wig_t, wig_q = [], [], [], [], [], [], []
    for t in times:
        root_t += [t * 0.5, 0.0, t]
        ident_q += [0.0, 0.0, 0.0, 1.0]
        ones += [1.0, 1.0, 1.0]
        arm_t += [0.0, 1.0, 0.0]
        arm_q += rf(quat((0, 0, 1), 60.0 * min(t, 1.0)))
        wig_t += [0.0, 1.0, 0.0]
        wig_q += rf(quat((1, 0, 0), 30.0 * math.sin(2 * math.pi * t)))
    samplers = [sampler(root_t, "VEC3"), sampler(ident_q, "VEC4"), sampler(ones, "VEC3"),
                sampler(arm_t, "VEC3"), sampler(arm_q, "VEC4"), sampler(ones, "VEC3"),
                sampler(wig_t, "VEC3"), sampler(wig_q, "VEC4"), sampler(ones, "VEC3")]
    channels = []
    for node in range(3):
        for k, path in enumerate(("translation", "rotation", "scale")):
            channels.append({"sampler": node * 3 + k, "target": {"node": node, "path": path}})
    g.add("animations", {"name": "Baked", "samplers": samplers, "channels": channels})
    write("baked.gltf", g.json())


# ---- textures.gltf ---------------------------------------------------------------------------------------------

def png(width, height, channels, pixel):
    """PNG bytes (8 bit, filter 0); pixel(x, y) -> a tuple of `channels` ints."""
    ctype = {1: 0, 2: 4, 3: 2, 4: 6}[channels]
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            raw += bytes(pixel(x, y))

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, ctype, 0, 0, 0)) +
            chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


def textures():
    g = Gltf()
    images = [
        ("Flat", png(40, 20, 4, lambda x, y: (200, 100, 50, 128))),
        ("Gradient", png(37, 23, 3, lambda x, y: (round(x * 255 / 36), round(y * 255 / 22), (x * 7 + y * 11) % 256))),
        ("Checker", png(32, 32, 1, lambda x, y: (255 if (x + y) % 2 else 0,))),
        ("FlatGA", png(30, 30, 2, lambda x, y: (90, 200))),
        ("Fringe", png(32, 32, 4, lambda x, y: (255, 255, 255, 255) if x % 2 == 0 else (0, 0, 0, 0))),
        ("Small", png(8, 8, 3, lambda x, y: (x * 30, y * 30, 77))),
    ]
    ap = g.accessor(FLOAT, "VEC3", [0, 0, 0, 1, 0, 0, 0, 1, 0], minmax=True)
    at = g.accessor(FLOAT, "VEC2", [0, 1, 1, 1, 0, 0])
    ai = g.accessor(USHORT, "SCALAR", [0, 1, 2])
    prims = []
    for i, (name, data) in enumerate(images):
        g.add("images", {"name": name, "uri": "data:image/png;base64," + base64.b64encode(data).decode()})
        g.add("textures", {"source": i})
        g.add("materials", {"name": name, "pbrMetallicRoughness": {"baseColorTexture": {"index": i}}})
        prims.append({"attributes": {"POSITION": ap, "TEXCOORD_0": at}, "indices": ai, "material": i})
    g.add("meshes", {"name": "Swatches", "primitives": prims})
    g.add("nodes", {"name": "Swatches", "mesh": 0})
    g.doc["scenes"] = [{"nodes": [0]}]
    g.doc["scene"] = 0
    write("textures.gltf", g.json())


if __name__ == "__main__":
    rig()
    nonuniform()
    fold()
    baked()
    textures()
