package dxmconverter;

import cinnamon.model.material.Material;
import cinnamon.model.material.MaterialTexture;
import cinnamon.model.obj.Face;
import cinnamon.model.obj.Group;
import cinnamon.model.obj.Mesh;
import cinnamon.utils.Maths;
import cinnamon.utils.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import static cinnamon.Client.LOGGER;

public class DXMConverter {

    private static final DecimalFormat df = new DecimalFormat("#.######", DecimalFormatSymbols.getInstance(Locale.US));
    private static final float NORMALS_EPSILON = 1e-6f;

    public static DXMModel loadDXM(String path) throws IOException {
        logMessage("## Loading DXM ##");
        boolean dxm = path.toLowerCase().endsWith(".dxm");
        Path dlmPath = Path.of(dxm ? path.substring(0, path.length() - 4) + ".dlm" : path);

        if (!Files.exists(dlmPath) && dxm)
            throw new RuntimeException("Unpacking of DXM files is not yet supported");

        DXMModel model = new DXMModel();
        model.header = new DXMHeader();

        InputStream in = Files.newInputStream(dlmPath);
        loadHeader(in, model.header);

        int[] flags = validateHeader(model.header);

        logMessage("Loading DXM groups...");
        model.groups = new DXMGroup[model.header.groupCount];
        for (int i = 0; i < model.header.groupCount; i++) {
            model.groups[i] = new DXMGroup();
            model.groups[i].offset = readUnsignedLong(in);
            model.groups[i].length = readUnsignedLong(in);

            short len = readUnsignedShort(in);
            if (len > 0) {
                byte[] texture = new byte[len - 1];
                in.read(texture);
                model.groups[i].texture = new String(texture);
                in.read();
            }
        }

        int vertcount = (int) model.header.vertexCount * 3 * Float.BYTES;
        int texcount = (int) model.header.vertexCount * 2 * Float.BYTES;
        int colcount = (int) model.header.vertexCount * 4 * Byte.BYTES;

        //vertex data
        logMessage("Loading DXM vertex data...");
        DXMData vertchunk = new DXMData();
        vertchunk.compressedSize = readUnsignedLong(in);
        vertchunk.uncompressedSize = readUnsignedLong(in);

        model.vertex = byteToFloat(in.readNBytes(vertcount));

        if (model.header.vertexCompositionFlags == flags[0]) {
            model.normal = byteToFloat(in.readNBytes(vertcount));
            model.uv = byteToFloat(in.readNBytes(texcount));
        } else if (model.header.vertexCompositionFlags == flags[1]) {
            model.color = in.readNBytes(colcount);
        }

        //index data
        logMessage("Loading DXM index data...");
        DXMData indchunk = new DXMData();
        indchunk.compressedSize = readUnsignedLong(in);
        indchunk.uncompressedSize = readUnsignedLong(in);

        for (int i = 0; i < model.header.groupCount; i++) {
            if (model.header.indexByteCount == 2) {
                model.groups[i].index16 = byteToShort(in.readNBytes((int) model.groups[i].length * 2));
            } else if (model.header.indexByteCount == 4) {
                model.groups[i].index32 = byteToInt(in.readNBytes((int) model.groups[i].length * 4));
            }
        }

        in.close();
        return model;
    }

    public static void loadHeader(InputStream in, DXMHeader header) throws IOException {
        logMessage("Loading DXM header...");

        header.nameCharacter0 = (byte) in.read();
        header.nameCharacter1 = (byte) in.read();
        header.nameCharacter2 = (byte) in.read();
        header.nameCharacter3 = (byte) in.read();

        header.majorVersion = (byte) in.read();
        header.minorVersion = (byte) in.read();

        header.encoding = (byte) in.read();
        header.compression = (byte) in.read();

        header.vertexCount = readUnsignedLong(in);
        header.vertexCompositionFlags = readUnsignedInt(in);

        header.groupCount = readUnsignedShort(in);

        header.indexFormat = (byte) in.read();
        header.indexByteCount = (byte) in.read();

        header.vertexTableAddr = readUnsignedLong(in);
        header.indexTableAddr = readUnsignedLong(in);
    }

    public static int[] validateHeader(DXMHeader header) {
        logMessage("Validating DXM header...");

        int flagMesh = 0, flagPC = 0;

        flagMesh |= DXMVertexFlag.Vertex_3_F32.bit;
        flagMesh |= DXMVertexFlag.Normal_3_F32.bit;
        flagMesh |= DXMVertexFlag.Texcoord_2_F32.bit;

        flagPC |= DXMVertexFlag.Vertex_3_F32.bit;
        flagPC |= DXMVertexFlag.Color_4_U8.bit;

        String ident = "" +
                (char) (header.nameCharacter0 & 0xFF) +
                (char) (header.nameCharacter1 & 0xFF) +
                (char) (header.nameCharacter2 & 0xFF) +
                (char) (header.nameCharacter3 & 0xFF);

        if (!ident.equals("DXM1"))
            throw new RuntimeException("Invalid file format of type: " + ident);

        if (header.majorVersion * 256 + header.minorVersion < 2 * 256 + 2)
            throw new RuntimeException("Outdated loader");

        if (header.encoding != DXMEncoding.DeInterleaved.ordinal())
            throw new RuntimeException("Unsupported encoding");

        if (header.compression != DXMCompression.NoCompression.ordinal())
            throw new RuntimeException("Unsupported compression");

        if (header.vertexCompositionFlags != flagMesh && header.vertexCompositionFlags != flagPC)
            throw new RuntimeException("Unsupported vertex format");

        return new int[]{flagMesh, flagPC};
    }

    public static void optimizeDXMModel(DXMModel model) {
        logMessage("## Optimizing DXM ##");

        boolean normal = model.normal != null;
        boolean uv = model.uv != null;

        HashMap<String, Integer> vertexMap = new HashMap<>();
        HashMap<String, Integer> normalMap = new HashMap<>();
        HashMap<String, Integer> uvMap = new HashMap<>();

        ArrayList<Float> newVertexList = new ArrayList<>();
        ArrayList<Float> newNormalList = new ArrayList<>();
        ArrayList<Float> newUVList = new ArrayList<>();

        logMessage("Processing vertices...");
        for (int i = 0; i < model.vertex.length; i += 3) {
            float x = model.vertex[i];
            float y = model.vertex[i + 1];
            float z = model.vertex[i + 2];
            String v = df.format(x) + " " + df.format(y) + " " + df.format(z);
            if (!vertexMap.containsKey(v)) {
                vertexMap.put(v, newVertexList.size() / 3);
                newVertexList.add(x);
                newVertexList.add(y);
                newVertexList.add(z);
            }
        }

        if (normal) {
            logMessage("Processing normals...");
            boolean onlyZeroes = true;
            for (int i = 0; i < model.normal.length; i += 3) {
                float x = model.normal[i];
                float y = model.normal[i + 1];
                float z = model.normal[i + 2];
                String n = df.format(x) + " " + df.format(y) + " " + df.format(z);
                if (!normalMap.containsKey(n)) {
                    normalMap.put(n, newNormalList.size() / 3);
                    newNormalList.add(x);
                    newNormalList.add(y);
                    newNormalList.add(z);
                    if (onlyZeroes) {
                        float magnitudeSq = x * x + y * y + z * z;
                        onlyZeroes = magnitudeSq <= NORMALS_EPSILON;
                    }
                }
            }
            if (onlyZeroes) {
                logMessage("All normals are effectively zero, ignoring...");
                normal = false;
            }
        }

        if (uv) {
            logMessage("Processing UVs...");
            for (int i = 0; i < model.uv.length; i += 2) {
                float u = model.uv[i];
                float v = model.uv[i + 1];
                String t = df.format(u) + " " + df.format(v);
                if (!uvMap.containsKey(t)) {
                    uvMap.put(t, newUVList.size() / 2);
                    newUVList.add(u);
                    newUVList.add(v);
                }
            }
        }

        logMessage("Updating model indices...");
        for (DXMGroup group : model.groups) {
            boolean index16 = group.index16 != null;

            if (!index16 && group.index32 == null)
                continue;

            int len = index16 ? group.index16.length : group.index32.length;

            group.vi = new int[len];
            if (normal) group.ni = new int[len];
            if (uv) group.ti = new int[len];

            for (int i = 0; i < len; i++) {
                int index = index16 ? group.index16[i] : group.index32[i];

                int vi = index * 3;
                group.vi[i] = vertexMap.get(df.format(model.vertex[vi]) + " " + df.format(model.vertex[vi + 1]) + " " + df.format(model.vertex[vi + 2]));

                if (normal)
                    group.ni[i] = normalMap.get(df.format(model.normal[vi]) + " " + df.format(model.normal[vi + 1]) + " " + df.format(model.normal[vi + 2]));

                if (uv) {
                    int ti = index * 2;
                    group.ti[i] = uvMap.get(df.format(model.uv[ti]) + " " + df.format(model.uv[ti + 1]));
                }
            }
        }

        logMessage("Updating model data...");
        model.v = new String[vertexMap.size()];
        for (HashMap.Entry<String, Integer> entry : vertexMap.entrySet()) {
            int i = entry.getValue();
            model.v[i] = entry.getKey();
        }

        if (normal) {
            model.vn = new String[normalMap.size()];
            for (HashMap.Entry<String, Integer> entry : normalMap.entrySet()) {
                int i = entry.getValue();
                model.vn[i] = entry.getKey();
            }
        }

        if (uv) {
            model.vt = new String[uvMap.size()];
            for (HashMap.Entry<String, Integer> entry : uvMap.entrySet()) {
                int i = entry.getValue();
                model.vt[i] = entry.getKey();
            }
        }
    }

    public static Mesh convertDXMtoOBJ(DXMModel model, String path) {
        Mesh mesh = new Mesh();

        boolean normals = model.vn != null;
        boolean uvs = model.vt != null;

        //mesh values
        for (String s : model.v) {
            String[] v = s.split(" ");
            mesh.getVertices().add(Maths.parseVec3(v[0], v[1], v[2]));
        }

        if (normals) {
            for (String s : model.vn) {
                String[] vn = s.split(" ");
                mesh.getNormals().add(Maths.parseVec3(vn[0], vn[1], vn[2]));
            }
        }

        if (uvs) {
            for (String s : model.vt) {
                String[] vt = s.split(" ");
                mesh.getUVs().add(Maths.parseVec2(vt[0], vt[1]));
            }
        }

        //groups
        Path folder = Path.of(path).getParent();
        for (DXMGroup group : model.groups) {
            if (group.texture == null)
                continue;

            String texture = group.texture.replaceAll("\\\\", "/");
            texture = texture.substring(texture.lastIndexOf("/") + 1);

            Group g = new Group(texture);
            mesh.getGroups().add(g);

            //material
            Material m = new Material(texture);
            g.setMaterial(m);
            mesh.getMaterials().put(texture, m);

            //texture
            Resource res = null;
            Path texPath = folder.resolve(texture);
            if (!Files.exists(texPath))
                texPath = folder.resolve("Textures").resolve(texture);
            if (Files.exists(texPath))
                res = new Resource("", texPath.toString());
            m.setAlbedo(res == null ? null : new MaterialTexture(res, false, false));

            //faces
            if (group.vi != null)
                for (int i = 0; i < group.vi.length; i += 3) {
                    List<Integer>
                            vi = new ArrayList<>(),
                            vt = new ArrayList<>(),
                            vn = new ArrayList<>();
                    g.getFaces().add(new Face(vi, vt, vn));

                    vi.add(group.vi[i]);
                    vi.add(group.vi[i + 1]);
                    vi.add(group.vi[i + 2]);

                    if (uvs) {
                        vt.add(group.ti[i]);
                        vt.add(group.ti[i + 1]);
                        vt.add(group.ti[i + 2]);
                    }

                    if (normals) {
                        vn.add(group.ni[i]);
                        vn.add(group.ni[i + 1]);
                        vn.add(group.ni[i + 2]);
                    }
                }
        }

        return mesh;
    }


    // -- helpers -- //


    private static void logMessage(String msg) {
        LOGGER.info(msg);
    }

    private static long readUnsignedLong(InputStream in) throws IOException {
        long value = 0;
        for (int i = 0; i < 8; i++)
            value |= (long) (in.read() & 0xFF) << (i * 8);
        return value;
    }

    private static int readUnsignedInt(InputStream in) throws IOException {
        int value = 0;
        for (int i = 0; i < 4; i++)
            value |= (in.read() & 0xFF) << (i * 8);
        return value;
    }

    private static short readUnsignedShort(InputStream in) throws IOException {
        return (short) ((in.read() & 0xFF) | ((in.read() & 0xFF) << 8));
    }

    private static float[] byteToFloat(byte[] src) {
        int count = src.length / Float.BYTES;
        float[] dst = new float[count];

        for (int i = 0; i < count; i++)
            dst[i] = Float.intBitsToFloat((src[i * 4] & 0xFF) | ((src[i * 4 + 1] & 0xFF) << 8) | ((src[i * 4 + 2] & 0xFF) << 16) | ((src[i * 4 + 3] & 0xFF) << 24));

        return dst;
    }

    private static int[] byteToInt(byte[] src) {
        int count = src.length / Integer.BYTES;
        int[] dst = new int[count];

        for (int i = 0; i < count; i++)
            dst[i] = (src[i * 4] & 0xFF) | ((src[i * 4 + 1] & 0xFF) << 8) | ((src[i * 4 + 2] & 0xFF) << 16) | ((src[i * 4 + 3] & 0xFF) << 24);

        return dst;
    }

    private static short[] byteToShort(byte[] src) {
        int count = src.length / Short.BYTES;
        short[] dst = new short[count];

        for (int i = 0; i < count; i++)
            dst[i] = (short) ((src[i * 2] & 0xFF) | ((src[i * 2 + 1] & 0xFF) << 8));

        return dst;
    }

    private static String applyFace(int x, int y, int z, int nx, int ny, int nz, int ux, int uy, int uz, boolean normals, boolean uvs) {
        if (normals && uvs) {
            return "%d/%d/%d %d/%d/%d %d/%d/%d".formatted(x, ux, nx, y, uy, ny, z, uz, nz);
        } else if (normals) {
            return "%d//%d %d//%d %d//%d".formatted(x, nx, y, ny, z, nz);
        } else if (uvs) {
            return "%d/%d %d/%d %d/%d".formatted(x, ux, y, uy, z, uz);
        } else {
            return "%s %d %d".formatted(x, y, z);
        }
    }

    private static String applyFace(DXMGroup group, int index, boolean normals, boolean uvs) {
        int x = group.vi[index] + 1;
        int y = group.vi[index + 1] + 1;
        int z = group.vi[index + 2] + 1;

        int nx = normals ? group.ni[index] + 1 : 0;
        int ny = normals ? group.ni[index + 1] + 1 : 0;
        int nz = normals ? group.ni[index + 2] + 1 : 0;

        int ux = uvs ? group.ti[index] + 1 : 0;
        int uy = uvs ? group.ti[index + 1] + 1 : 0;
        int uz = uvs ? group.ti[index + 2] + 1 : 0;

        return applyFace(x, y, z, nx, ny, nz, ux, uy, uz, normals, uvs);
    }


    // -- structure -- //


    public static class DXMHeader {
        public byte nameCharacter0, nameCharacter1, nameCharacter2, nameCharacter3;

        public byte majorVersion, minorVersion;
        public byte encoding, compression;

        public long vertexCount; //ulong
        public int vertexCompositionFlags; //uint

        public short groupCount; //ushort

        public byte indexFormat;
        public byte indexByteCount;

        public long vertexTableAddr, indexTableAddr; //ulong
    }

    public enum DXMEncoding {
        Inteleaved,
        DeInterleaved,
        BytePack
    }

    public enum DXMCompression {
        NoCompression,
        LZ77
    }

    public enum DXMVertexFlag {
        Vertex_3_F32(1),
        Normal_3_F32(1 << 1),
        Texcoord_2_F32(1 << 2),
        Color_4_U8(1 << 3);

        public final int bit;

        DXMVertexFlag(int bit) {
            this.bit = bit;
        }
    }

    public static class DXMData {
        public long compressedSize, uncompressedSize; //ulong
    }

    public static class DXMGroup {
        public long offset, length; //ulong
        public String texture;
        public short[] index16;
        public int[] index32;

        public int[] vi, ni, ti;
    }

    public static class DXMModel {
        public DXMHeader header;
        public DXMGroup[] groups;
        public float[] vertex, normal, uv;
        public byte[] color;

        public String[] v, vn, vt;
    }
}
