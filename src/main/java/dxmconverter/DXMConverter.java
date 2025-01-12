package dxmconverter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class DXMConverter {

    public static void main(String[] args) throws IOException {
        if (args.length == 0)
            throw new RuntimeException("No file was given");

        String path = args[0];
        if (!Files.exists(Path.of(path)))
            throw new RuntimeException("File not found: " + path);

        DXMModel model = loadDXM(path);
        writeDXMasOBJ(model, path);
    }

    private static DXMModel loadDXM(String path) throws IOException {
        boolean dxm = path.toLowerCase().endsWith(".dxm");
        Path dlmPath = Path.of(dxm ? path.substring(0, path.length() - 4) + ".dlm" : path);

        if (!Files.exists(dlmPath) && dxm)
            throw new RuntimeException("Unpacking of DXM files is not supported");

        DXMModel model = new DXMModel();
        model.header = new DXMHeader();

        InputStream in = Files.newInputStream(dlmPath);
        loadHeader(in, model.header);

        Pair<Integer, Integer> flags = validateHeader(model.header);

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
        DXMData vertchunk = new DXMData();
        vertchunk.compressedSize = readUnsignedLong(in);
        vertchunk.uncompressedSize = readUnsignedLong(in);

        model.vertex = byteToFloat(in.readNBytes(vertcount));

        if (model.header.vertexCompositionFlags == flags.first) {
            model.normal = byteToFloat(in.readNBytes(vertcount));
            model.uv = byteToFloat(in.readNBytes(texcount));
        } else if (model.header.vertexCompositionFlags == flags.second) {
            model.color = in.readNBytes(colcount);
        }

        //index data
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

    private static Pair<Integer, Integer> validateHeader(DXMHeader header) {
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
            throw new RuntimeException("Outdated loader.");

        if (header.encoding != DXMEncoding.DeInterleaved.ordinal())
            throw new RuntimeException("Unsupported encoding.");

        if (header.compression != DXMCompression.NoCompression.ordinal())
            throw new RuntimeException("Unsupported compression.");

        if (header.vertexCompositionFlags != flagMesh && header.vertexCompositionFlags != flagPC)
            throw new RuntimeException("Unsupported vertex format.");

        return new Pair<>(flagMesh, flagPC);
    }

    private static void writeDXMasOBJ(DXMModel model, String path) throws IOException {
        String rawPath = path.substring(0, path.length() - 4);
        String srcFolder = rawPath.substring(0, rawPath.lastIndexOf("\\") + 1);
        String filename = rawPath.substring(rawPath.lastIndexOf("\\") + 1);

        Path folder = Path.of("./", filename);
        if (!Files.exists(folder))
            Files.createDirectory(folder);

        StringBuilder obj = new StringBuilder();
        StringBuilder mtl = new StringBuilder();

        obj.append("# DXM to OBJ Converter\n");
        obj.append("# Generated by Meii\n\n");

        mtl.append("# DXM to OBJ Converter\n");
        mtl.append("# Generated by Meii\n\n");

        obj.append("mtllib %s\n".formatted(filename + ".mtl"));
        obj.append("o %s\n".formatted(filename));

        for (int i = 0; i < model.vertex.length; i += 3)
            obj.append("v %f %f %f\n".formatted(model.vertex[i], model.vertex[i + 1], model.vertex[i + 2]));

        if (model.normal != null)
            for (int i = 0; i < model.normal.length; i += 3)
                obj.append("vn %f %f %f\n".formatted(model.normal[i], model.normal[i + 1], model.normal[i + 2]));

        if (model.uv != null)
            for (int i = 0; i < model.uv.length; i += 2)
                obj.append("vt %f %f\n".formatted(model.uv[i], model.uv[i + 1]));

        for (DXMGroup group : model.groups) {
            if (group.texture != null) {
                Path texPath = Path.of(srcFolder, group.texture);
                if (!Files.exists(texPath))
                    texPath = Path.of(srcFolder, "Textures", group.texture);
                if (Files.exists(texPath))
                    Files.copy(texPath, folder.resolve(group.texture));
            }

            obj.append("usemtl %s\n".formatted(group.texture));

            if (group.index16 != null) {
                for (int i = 0; i < group.index16.length; i += 3) {
                    obj.append("f %d/%d/%d %d/%d/%d %d/%d/%d\n".formatted(
                            group.index16[i] + 1, group.index16[i] + 1, group.index16[i] + 1,
                            group.index16[i + 1] + 1, group.index16[i + 1] + 1, group.index16[i + 1] + 1,
                            group.index16[i + 2] + 1, group.index16[i + 2] + 1, group.index16[i + 2] + 1
                    ));
                }
            }

            if (group.index32 != null) {
                for (int i = 0; i < group.index32.length; i += 3) {
                    obj.append("f %d/%d/%d %d/%d/%d %d/%d/%d\n".formatted(
                            group.index32[i] + 1, group.index32[i] + 1, group.index32[i] + 1,
                            group.index32[i + 1] + 1, group.index32[i + 1] + 1, group.index32[i + 1] + 1,
                            group.index32[i + 2] + 1, group.index32[i + 2] + 1, group.index32[i + 2] + 1
                    ));
                }
            }

            mtl.append("newmtl %s\n".formatted(group.texture));
            mtl.append("map_Kd %s\n".formatted(group.texture));
        }

        Files.write(folder.resolve("%s.obj".formatted(filename)), obj.toString().getBytes());
        Files.write(folder.resolve("%s.mtl".formatted(filename)), mtl.toString().getBytes());
    }


    // -- helpers -- //


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
        public long offset; //ulong
        public long length; //ulong
        public String texture;
        public short[] index16;
        public int[] index32;
    }

    private static class DXMModel {
        public DXMHeader header;
        public DXMGroup[] groups;
        public float[] vertex;
        public float[] normal;
        public float[] uv;
        public byte[] color;
    }

    private static class Pair<T1, T2> {
        public T1 first;
        public T2 second;

        public Pair(T1 first, T2 second) {
            this.first = first;
            this.second = second;
        }
    }
}
