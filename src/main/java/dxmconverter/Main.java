package dxmconverter;

import cinnamon.Cinnamon;
import cinnamon.Client;
import cinnamon.utils.Resource;

public class Main {

    public static void main(String[] args) {
        Cinnamon.TITLE = "DXM Converter";
        Cinnamon.ICON = new Resource("dxmconverter", "icon/32.png");
        Client.getInstance().mainScreen = DXMScreen::new;
        new Cinnamon().run();
    }
}
