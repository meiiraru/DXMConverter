package dxmconverter;

import cinnamon.gui.ParentedScreen;
import cinnamon.gui.Screen;
import cinnamon.gui.Toast;
import cinnamon.gui.widgets.ContainerGrid;
import cinnamon.gui.widgets.WidgetList;
import cinnamon.gui.widgets.types.*;
import cinnamon.model.ModelManager;
import cinnamon.model.obj.Mesh;
import cinnamon.parsers.ObjExporter;
import cinnamon.parsers.ObjLoader;
import cinnamon.render.MatrixStack;
import cinnamon.render.batch.VertexConsumer;
import cinnamon.render.model.ObjRenderer;
import cinnamon.render.shader.Shader;
import cinnamon.render.shader.Shaders;
import cinnamon.text.Text;
import cinnamon.utils.*;
import org.joml.Matrix4f;

import java.nio.file.Path;

import static cinnamon.Client.LOGGER;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.glClear;

public class DXMViewerScreen extends ParentedScreen {

    private static final Resource
            CUBE = new Resource("dxmconverter", "models/cube.obj"),
            BACK = new Resource("textures/gui/icons/back.png"),
            FORWARDS = new Resource("textures/gui/icons/forwards.png"),
            RESET = new Resource("textures/gui/icons/reload.png");

    private static final Path EXPORT_FOLDER = Path.of("./");

    private final ModelViewer modelViewer = new ModelViewer(0, 0, 1, 1);
    private final Mesh model;
    private final String name;

    private final Slider
            rotX = new Slider(0, 0, 1),
            rotY = new Slider(0, 0, 1),
            rotZ = new Slider(0, 0, 1);

    private final Checkbox
            flipX = new Checkbox(0, 0, Text.of("Flip X")),
            flipY = new Checkbox(0, 0, Text.of("Flip Y")),
            flipZ = new Checkbox(0, 0, Text.of("Flip Z"));

    private WidgetList list;
    private Button arrow;
    private float listX;
    private boolean showList;

    public DXMViewerScreen(Screen parentScreen, String modelFile) {
        super(parentScreen);

        modelFile = modelFile.replaceAll("\\\\", "/");
        this.name = modelFile.substring(modelFile.lastIndexOf('/') + 1, modelFile.lastIndexOf('.'));

        Mesh model;
        try {
            if (modelFile.endsWith(".obj"))
                model = ObjLoader.load(new Resource("", modelFile));
            else {
                DXMConverter.DXMModel dxm = DXMConverter.loadDXM(modelFile);
                DXMConverter.optimizeDXMModel(dxm);
                model = DXMConverter.convertDXMtoOBJ(dxm, modelFile);
            }
        } catch (Exception e) {
            model = null;
            LOGGER.error("Failed to load model", e);
            Toast.addToast(Text.of("Failed to load model\n" + e.getMessage())).type(Toast.ToastType.ERROR);
            close();
        }

        this.model = model;

        modelViewer.setDefaultRot(0, 0);
        if (model != null)
            modelViewer.setModel(new ObjRenderer(model));

        rotX.setMin(-180); rotX.setMax(180);
        rotY.setMin(-180); rotY.setMax(180);
        rotZ.setMin(-180); rotZ.setMax(180);
    }

    @Override
    public void init() {
        super.init();

        //main list
        int w = Math.max((int) (width * 0.3f), 150);
        list = new WidgetList(0, 4, w, height - 8, 16);
        list.setX(width + w / 2 + 4);
        list.setBackground(true);
        addWidget(list);

        //rotation

        list.addWidget(new Label(0, 0, Text.of("Rotation")));

        //rotation list
        ContainerGrid rotGrid = new ContainerGrid(0, 0, 8, 3);
        rotGrid.setAlignment(Alignment.CENTER);
        list.addWidget(rotGrid);

        rotGrid.addWidget(new Label(0, 0, Text.of("X")));
        rotX.setWidth(w - 48);
        rotGrid.addWidget(rotX);
        Button resetX = new Button(0, 0, 12, 12, null, b -> rotX.setValue(0));
        resetX.setImage(RESET);
        rotGrid.addWidget(resetX);

        rotGrid.addWidget(new Label(0, 0, Text.of("Y")));
        rotY.setWidth(w - 48);
        rotGrid.addWidget(rotY);
        Button resetY = new Button(0, 0, 12, 12, null, b -> rotY.setValue(0));
        resetY.setImage(RESET);
        rotGrid.addWidget(resetY);

        rotGrid.addWidget(new Label(0, 0, Text.of("Z")));
        rotZ.setWidth(w - 48);
        rotGrid.addWidget(rotZ);
        Button resetZ = new Button(0, 0, 12, 12, null, b -> rotZ.setValue(0));
        resetZ.setImage(RESET);
        rotGrid.addWidget(resetZ);

        //flip
        list.addWidget(new Label(0, 0, Text.of("Flip")));

        ContainerGrid flipGrid = new ContainerGrid(0, 0, 8);
        list.addWidget(flipGrid);

        flipGrid.addWidget(flipX);
        flipGrid.addWidget(flipY);
        flipGrid.addWidget(flipZ);

        //buttons
        list.addWidget(new Button(0, 0, w - 8, 16, Text.of("Export OBJ"), b -> {
            try {
                Matrix4f pose = new Matrix4f();
                pose.scale(flipX.isToggled() ? -1 : 1, flipY.isToggled() ? -1 : 1, flipZ.isToggled() ? -1 : 1);
                pose.rotate(Rotation.Z.rotationDeg(rotZ.getValue()));
                pose.rotate(Rotation.Y.rotationDeg(rotY.getValue()));
                pose.rotate(Rotation.X.rotationDeg(rotX.getValue()));

                ObjExporter.export(name, model, pose, EXPORT_FOLDER);
                Toast.addToast(Text.of("Model exported")).type(Toast.ToastType.SUCCESS);
            } catch (Exception e) {
                LOGGER.error("Failed to export model", e);
                Toast.addToast(Text.of("Failed to export model")).type(Toast.ToastType.ERROR);
            }
        }));

        list.setHeight(list.getWidgetsHeight());
        list.setY((height - list.getHeight()) / 2);

        //arrow
        arrow = new Button(width - 4 - 16, (height - 16) / 2, 16, 16, Text.of(""), b -> {
            showList = !showList;
            arrow.setImage(showList ? FORWARDS : BACK);
            list.setActive(showList);
        });
        showList = !showList;
        arrow.onRun();
        addWidget(arrow);

        //model viewer
        modelViewer.setExtraRendering(matrices -> {
            matrices.scale(flipX.isToggled() ? -1 : 1, flipY.isToggled() ? -1 : 1, flipZ.isToggled() ? -1 : 1);
            matrices.rotate(Rotation.Z.rotationDeg(rotZ.getValue()));
            matrices.rotate(Rotation.Y.rotationDeg(rotY.getValue()));
            matrices.rotate(Rotation.X.rotationDeg(rotX.getValue()));
        });
        modelViewer.setDimensions(width, height);
        addWidget(modelViewer);
    }

    @Override
    protected void addBackButton() {
        //nope
        //super.addBackButton();
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        float d = UIHelper.tickDelta(0.6f);
        listX = Maths.lerp(listX, showList ? list.getWidth() : -10, d);
        list.setX(width - (int) listX + list.getWidth() / 2);
        arrow.setX(width - 4 - 16 - (int) Math.max(listX, 0));

        super.render(matrices, mouseX, mouseY, delta);

        VertexConsumer.finishAllBatches(client.camera);
        glClear(GL_DEPTH_BUFFER_BIT);
        renderGizmos(matrices);
    }

    private void renderGizmos(MatrixStack matrices) {
        float s = 60 / client.window.guiScale;

        matrices.push();
        matrices.translate(s, s, 0);
        matrices.scale(s, -s, s);

        matrices.rotate(Rotation.X.rotationDeg(-modelViewer.getRotX()));
        matrices.rotate(Rotation.Y.rotationDeg(180 - modelViewer.getRotY()));

        Shader old = Shader.activeShader;
        Shaders.MODEL.getShader().use().setup(client.camera);
        ModelManager.load(CUBE).render(matrices);

        //matrices.scale(1 / s, -1 / s, 1 / s);
        //matrices.translate(0, -s * 0.5f - 5, 0);

        //float len = 10;
        //VertexConsumer.GUI.consume(GeometryHelper.cube(matrices, 1, 0, 0, len, 1, 1, 0xFFFF0000));
        //VertexConsumer.GUI.consume(GeometryHelper.cube(matrices, 0, 0, 0, 1, -len, 1, 0xFF00FF00));
        //VertexConsumer.GUI.consume(GeometryHelper.cube(matrices, 0, 0, 0, 1, 1, len, 0xFF0000FF));

        matrices.pop();
        old.use();
    }
}
