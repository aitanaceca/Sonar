package edu.asu.stratego.gui.board;

import edu.asu.stratego.gui.ClientStage;
import javafx.scene.layout.GridPane;

public class AttackPanel {

    private static GridPane attackPanel = new GridPane();

    public AttackPanel() {
        final double UNIT = ClientStage.getUnit();
        attackPanel.setMaxHeight(UNIT * 2);
        attackPanel.setMaxWidth(UNIT * 5);

        // Panel background.
        String backgroundURL = "edu/asu/stratego/media/images/board/setup_panel.png";
        attackPanel.setStyle("-fx-background-image: url(" + backgroundURL + "); " +
                "-fx-background-size: " + UNIT * 10 + " " + UNIT * 5 + ";" +
                "-fx-background-repeat: stretch;");
    }
}
