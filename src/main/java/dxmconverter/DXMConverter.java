package dxmconverter;

import javax.swing.JOptionPane;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

public class DXMConverter {

    private static final DecimalFormat df = new DecimalFormat("#.######", DecimalFormatSymbols.getInstance(Locale.US));
    private static final float NORMALS_EPSILON = 1e-6f;

    public static void main(String[] args) {
        if (args.length == 0) {
            showMessage("No file was given", JOptionPane.ERROR_MESSAGE);
            throw new RuntimeException("No file was given");
        }

        String path = args[0];
        if (!Files.exists(Path.of(path))) {
            showMessage("File not found: " + path, JOptionPane.ERROR_MESSAGE);
            throw new RuntimeException("File not found: " + path);
        }

        long start = System.currentTimeMillis();
        logMessage("Loading file: " + path);

        DXMModel model;
        try {
            model = loadDXM(path);
        } catch (Exception e) {
            showMessage("Failed to load DXM file\n" + e.getMessage(), JOptionPane.ERROR_MESSAGE);
            throw new RuntimeException("Failed to load DXM file", e);
        }
        long start2 = System.currentTimeMillis();
        logMessage("DXM model loaded in " + (start2 - start) + "ms");

        optimizeDXMModel(model);
        long start3 = System.currentTimeMillis();
        logMessage("DXM model optimized in " + (start3 - start2) + "ms");

        try {
            writeDXMasOBJ(model, path);
        } catch (Exception e) {
            showMessage("Failed to write OBJ file\n" + e.getMessage(), JOptionPane.ERROR_MESSAGE);
            throw new RuntimeException("Failed to write OBJ file", e);
        }
        long start4 = System.currentTimeMillis();
        logMessage("OBJ file written in " + (start4 - start3) + "ms");

        String msg = "Done! (" + (start4 - start) + "ms) :3";
        logMessage(msg);
        showMessage(msg, JOptionPane.INFORMATION_MESSAGE);
    }

    private static DXMModel loadDXM(String path) throws IOException {
        logMessage("## Loading DXM ##");
        boolean dxm = path.toLowerCase().endsWith(".dxm");
        Path dlmPath = Path.of(dxm ? path.substring(0, path.length() - 4) + ".dlm" : path);

        if (!Files.exists(dlmPath) && dxm) {
            showMessage("Unpacking of DXM files is not yet supported", JOptionPane.ERROR_MESSAGE);
            throw new RuntimeException("Unpacking of DXM files is not yet supported");
        }

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

    private static void loadHeader(InputStream in, DXMHeader header) throws IOException {
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

    private static int[] validateHeader(DXMHeader header) {
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

    private static void optimizeDXMModel(DXMModel model) {
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

    private static void writeDXMasOBJ(DXMModel model, String path) throws IOException {
        logMessage("## Converting to OBJ ##");
        String rawPath = path.substring(0, path.length() - 4);
        String srcFolder = rawPath.substring(0, rawPath.lastIndexOf("\\") + 1);
        String filename = rawPath.substring(rawPath.lastIndexOf("\\") + 1);

        logMessage("Creating OBJ folder...");
        Path folder = Path.of("./", filename);
        if (!Files.exists(folder)) {
            Files.createDirectory(folder);
        } else {
            for (int i = 1; Files.exists(folder); i++)
                folder = Path.of("./", filename + " (" + i + ")");
            Files.createDirectory(folder);
        }

        StringBuilder obj = new StringBuilder();
        StringBuilder mtl = new StringBuilder();

        obj.append("# DXM to OBJ Converter\n");
        obj.append("# Generated by Meii\n\n");

        mtl.append("# DXM to OBJ Converter\n");
        mtl.append("# Generated by Meii\n\n");

        obj.append("mtllib %s\n".formatted(filename + ".mtl"));
        obj.append("o %s\n".formatted(filename));

        boolean normals = model.vn != null;
        boolean uvs = model.vt != null;

        logMessage("Writing OBJ vertices...");
        for (int i = 0; i < model.v.length; i++)
            obj.append("v ").append(model.v[i]).append("\n");

        if (normals) {
            logMessage("Writing OBJ normals...");
            for (int i = 0; i < model.vn.length; i++)
                obj.append("vn ").append(model.vn[i]).append("\n");
        }

        if (uvs) {
            logMessage("Writing OBJ UVs...");
            for (int i = 0; i < model.vt.length; i++)
                obj.append("vt ").append(model.vt[i]).append("\n");
        }

        logMessage("Writing OBJ groups and faces...");
        for (DXMGroup group : model.groups) {
            if (group.texture != null) {
                Path texPath = Path.of(srcFolder, group.texture);
                if (!Files.exists(texPath))
                    texPath = Path.of(srcFolder, "Textures", group.texture);
                if (Files.exists(texPath))
                    Files.copy(texPath, folder.resolve(group.texture));
            }

            obj.append("usemtl %s\n".formatted(group.texture));

            if (group.vi != null)
                for (int i = 0; i < group.vi.length; i += 3)
                    obj.append("f %s\n".formatted(applyFace(group, i, normals, uvs)));

            else if (group.index16 != null)
                for (int i = 0; i < group.index16.length; i += 3)
                    obj.append("f %s\n".formatted(applyFace(group.index16[i] + 1, group.index16[i + 1] + 1, group.index16[i + 2] + 1, normals, uvs)));

            else if (group.index32 != null)
                for (int i = 0; i < group.index32.length; i += 3)
                    obj.append("f %s\n".formatted(applyFace(group.index32[i] + 1, group.index32[i + 1] + 1, group.index32[i + 2] + 1, normals, uvs)));

            mtl.append("newmtl %s\n".formatted(group.texture));
            mtl.append("map_Kd %s\n".formatted(group.texture));
        }

        logMessage("Saving files...");
        Files.write(folder.resolve("%s.obj".formatted(filename)), obj.toString().getBytes());
        Files.write(folder.resolve("%s.mtl".formatted(filename)), mtl.toString().getBytes());
    }


    // -- helpers -- //


    private static void showMessage(String msg, int type) {
        JOptionPane.showMessageDialog(null, msg, "DXM Converter", type);
    }

    private static void logMessage(String msg) {
        System.out.println(msg);
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

    private static String applyFace(int x, int y, int z, boolean normals, boolean uvs) {
        return applyFace(x, y, z, x, y, z, x, y, z, normals, uvs);
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


    private static class DXMHeader {
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

    private enum DXMEncoding {
        Inteleaved,
        DeInterleaved,
        BytePack
    }

    private enum DXMCompression {
        NoCompression,
        LZ77
    }

    private enum DXMVertexFlag {
        Vertex_3_F32(1),
        Normal_3_F32(1 << 1),
        Texcoord_2_F32(1 << 2),
        Color_4_U8(1 << 3);

        public final int bit;

        DXMVertexFlag(int bit) {
            this.bit = bit;
        }
    }

    private static class DXMData {
        public long compressedSize, uncompressedSize; //ulong
    }

    private static class DXMGroup {
        public long offset, length; //ulong
        public String texture;
        public short[] index16;
        public int[] index32;

        public int[] vi, ni, ti;
    }

    private static class DXMModel {
        public DXMHeader header;
        public DXMGroup[] groups;
        public float[] vertex, normal, uv;
        public byte[] color;

        public String[] v, vn, vt;
    }
}
