package dxmconverter;

import cinnamon.gui.GUIStyle;
import cinnamon.gui.Screen;
import cinnamon.gui.Toast;
import cinnamon.model.GeometryHelper;
import cinnamon.render.MatrixStack;
import cinnamon.render.batch.VertexConsumer;
import cinnamon.text.Text;
import cinnamon.utils.Alignment;
import cinnamon.utils.Resource;

public class DXMScreen extends Screen {

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        super.render(matrices, mouseX, mouseY, delta);
        VertexConsumer.GUI.consume(GeometryHelper.quad(matrices, (int) (width / 2f) - 64, (int) (height / 2f) - 64, 128, 128), new Resource("dxmconverter", "textures/model_icon.png"));
        Text.of("drop obj/dlm files here\nto open a new model").render(VertexConsumer.FONT, matrices, (int) (width / 2f), (int) (height / 2f) + 64, Alignment.CENTER);
        Text.of("Welcome!").render(VertexConsumer.FONT, matrices, (int) (width / 2f), (int) (height / 2f) - 64 - GUIStyle.getDefault().font.lineHeight, Alignment.CENTER);
    }

    @Override
    public boolean filesDropped(String[] files) {
        for (String file : files) {
            if (file.matches("^.+(\\.dlm|\\.obj)$")) {
                client.setScreen(new DXMViewerScreen(this, file));
                return true;
            }
        }

        Toast.addToast(Text.of("Invalid file, must be .dlm or .obj")).type(Toast.ToastType.ERROR);
        return false;
    }
}
