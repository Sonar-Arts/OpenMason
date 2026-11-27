"""Export Blender corner normals as a Cenda normals fixture (OpenMason #169).

Run inside Blender (Scripting tab, or `blender file.blend --background --python export_blender_normals.py -- out.normals`)
with the mesh object to export ACTIVE. Writes `<object name>.normals` next to this script unless a path follows `--`.
The normals ctest (tests/normals_test.cpp) reads every *.normals file in this folder and checks Cenda's
co_mesh_normals against Blender's corner normals, every corner within 0.5 degrees.

What is exported (the EVALUATED mesh, so modifiers such as "Smooth by Angle" are baked into its sharp edges):
  v x y z         one soup vertex per face corner (Blender loop), object space
  t a b c face    loop triangles (soup vertices = loop indices), face = polygon index
  s face          polygons with use_smooth
  e a b           sharp edges, as the two loops of the first polygon that uses the edge
  a degrees       Blender < 4.1 only: the mesh's Auto Smooth angle when use_auto_smooth is on
  n x y z         Blender's corner normal of every loop

Custom split normals are not exported (Cenda's custom normals are absolute, not Blender's fan-relative ones): the
script refuses a mesh that has them. Not verified in CI: written for Blender 3.6 and 4.1+.
"""

import os
import sys

import bpy


def output_path(obj):
    if "--" in sys.argv:
        rest = sys.argv[sys.argv.index("--") + 1:]
        if rest:
            return rest[0]
    here = os.path.dirname(os.path.abspath(__file__)) if "__file__" in globals() else os.getcwd()
    return os.path.join(here, obj.name + ".normals")


def main():
    obj = bpy.context.active_object
    if obj is None or obj.type != "MESH":
        raise SystemExit("select a mesh object")
    depsgraph = bpy.context.evaluated_depsgraph_get()
    evaluated = obj.evaluated_get(depsgraph)
    mesh = evaluated.to_mesh()
    try:
        if mesh.has_custom_normals:
            raise SystemExit("the mesh has custom split normals; clear them first")
        legacy = bpy.app.version < (4, 1, 0)
        if legacy:
            mesh.calc_normals_split()
            normals = [tuple(loop.normal) for loop in mesh.loops]
        else:
            normals = [tuple(n.vector) for n in mesh.corner_normals]
        mesh.calc_loop_triangles()

        sharp = set()
        attr = mesh.attributes.get("sharp_edge")
        # Before 4.1, sharp edges only count with Auto Smooth on.
        honour_sharp = not legacy or getattr(mesh, "use_auto_smooth", False)
        for e in (mesh.edges if honour_sharp else []):
            flagged = attr.data[e.index].value if attr is not None else e.use_edge_sharp
            if flagged:
                sharp.add(e.index)
        pairs = {}
        for poly in mesh.polygons:
            for k in range(poly.loop_total):
                li = poly.loop_start + k
                edge = mesh.loops[li].edge_index
                if edge in sharp and edge not in pairs:
                    nxt = poly.loop_start + (k + 1) % poly.loop_total
                    pairs[edge] = (li, nxt)

        path = output_path(obj)
        with open(path, "w", encoding="utf-8") as out:
            out.write("# Blender %s, object %s\n" % (bpy.app.version_string, obj.name))
            for loop in mesh.loops:
                co = mesh.vertices[loop.vertex_index].co
                out.write("v %.9g %.9g %.9g\n" % (co.x, co.y, co.z))
            for tri in mesh.loop_triangles:
                a, b, c = tri.loops
                out.write("t %d %d %d %d\n" % (a, b, c, tri.polygon_index))
            for poly in mesh.polygons:
                if poly.use_smooth:
                    out.write("s %d\n" % poly.index)
            for a, b in sorted(pairs.values()):
                out.write("e %d %d\n" % (a, b))
            if legacy and getattr(mesh, "use_auto_smooth", False):
                import math
                out.write("a %.9g\n" % math.degrees(mesh.auto_smooth_angle))
            for n in normals:
                out.write("n %.9g %.9g %.9g\n" % n)
        print("wrote", path)
    finally:
        evaluated.to_mesh_clear()


main()
