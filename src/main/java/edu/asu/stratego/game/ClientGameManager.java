package edu.asu.stratego.game;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.logging.Level;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.util.Duration;
import edu.asu.stratego.game.board.ClientSquare;
import edu.asu.stratego.gui.BoardScene;
import edu.asu.stratego.gui.ClientStage;
import edu.asu.stratego.gui.ConnectionScene;
import edu.asu.stratego.gui.board.BoardTurnIndicator;
import edu.asu.stratego.media.ImageConstants;
import edu.asu.stratego.util.HashTables;

import static com.sun.xml.internal.ws.spi.db.BindingContextFactory.LOGGER;

/**
 * Task to handle the Stratego game on the client-side.
 */
public class ClientGameManager implements Runnable {

    private static final Object setupPieces = new Object();
    private static final Object sendMove = new Object();
    private static final Object waitFade = new Object();
    private static final Object waitVisible = new Object();

    private ObjectOutputStream toServer;
    private ObjectInputStream fromServer;

    private ClientStage stage;

    /**
     * Creates a new instance of ClientGameManager.
     *
     * @param stage the stage that the client is set in
     */
    public ClientGameManager(ClientStage stage) {
        this.stage = stage;
    }

    /**
     * See ServerGameManager's run() method to understand how the client
     * interacts with the server.
     *
     //* @see edu.asu.stratego.Game.ServerGameManager
     */
    @Override
    public void run() {
        connectToServer();
        waitForOpponent();

        setupBoard();
        playGame();
    }

    /**
     * @return Object used for communication between the Setup Board GUI and
     * the ClientGameManager to indicate when the player has finished setting
     * up their pieces.
     */
    public static Object getSetupPieces() {
        return setupPieces;
    }

    /**
     * Executes the ConnectToServer thread. Blocks the current thread until
     * the ConnectToServer thread terminates.
     *
     * @see ConnectionScene.ConnectToServer
     */
    private void connectToServer() {
        try {
            ConnectionScene.ConnectToServer connectToServer =
                    new ConnectionScene.ConnectToServer();
            Thread serverConnect = new Thread(connectToServer);
            serverConnect.setDaemon(true);
            serverConnect.start();
            serverConnect.join();
        } catch (InterruptedException e) {
            LOGGER.log(Level.WARNING, "Interrupted connection", e);
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Establish I/O streams between the client and the server. Send player
     * information to the server. Then, wait until an object containing player
     * information about the opponent is received from the server.
     *
     * <p>
     * After the player information has been sent and opponent information has
     * been received, the method terminates indicating that it is time to set up
     * the game.
     * </p>
     */
    private void waitForOpponent() {
        Platform.runLater(() -> {
            stage.setWaitingScene();
        });
        try {
            // I/O Streams.
            toServer = new ObjectOutputStream(edu.asu.stratego.game.ClientSocket.getInstance().getOutputStream());
            fromServer = new ObjectInputStream(edu.asu.stratego.game.ClientSocket.getInstance().getInputStream());

            // Exchange player information.
            toServer.writeObject(edu.asu.stratego.game.Game.getPlayer());
            edu.asu.stratego.game.Game.setOpponent((edu.asu.stratego.game.Player) fromServer.readObject());

            // Infer player color from opponent color.
            if (edu.asu.stratego.game.Game.getOpponent().getColor() == edu.asu.stratego.game.PieceColor.RED)
                edu.asu.stratego.game.Game.getPlayer().setColor(edu.asu.stratego.game.PieceColor.BLUE);
            else
                edu.asu.stratego.game.Game.getPlayer().setColor(edu.asu.stratego.game.PieceColor.RED);
        } catch (IOException | ClassNotFoundException e) {
            LOGGER.log(Level.WARNING, "Opponent not found", e);
        }
    }

    /**
     * Switches to the game setup scene. Players will place their pieces to
     * their initial starting positions. Once the pieces are placed, their
     * positions are sent to the server.
     */
    private void setupBoard() {
        Platform.runLater(() -> {
            stage.setBoardScene();
        });

        synchronized (setupPieces) {
            try {
                // Wait for the player to set up their pieces.
                setupPieces.wait();
                edu.asu.stratego.game.Game.setStatus(edu.asu.stratego.game.GameStatus.WAITING_OPP);

                // Send initial piece positions to server.
                edu.asu.stratego.game.SetupBoard initial = new edu.asu.stratego.game.SetupBoard();
                initial.getPiecePositions();
                toServer.writeObject(initial);

                // Receive opponent's initial piece positions from server.
                final edu.asu.stratego.game.SetupBoard opponentInitial = (edu.asu.stratego.game.SetupBoard) fromServer.readObject();

                // Place the opponent's pieces on the board.
                Platform.runLater(() -> {
                    for (int row = 0; row < 4; ++row) {
                        for (int col = 0; col < 10; ++col) {
                            ClientSquare square = edu.asu.stratego.game.Game.getBoard().getSquare(row, col);
                            square.setPiece(opponentInitial.getPiece(row, col));

                            if (edu.asu.stratego.game.Game.getPlayer().getColor() == edu.asu.stratego.game.PieceColor.RED)
                                square.getPiecePane().setPiece(ImageConstants.BLUE_BACK);
                            else
                                square.getPiecePane().setPiece(ImageConstants.RED_BACK);
                        }
                    }
                });
            } catch (InterruptedException | IOException | ClassNotFoundException e) {
                LOGGER.log(Level.WARNING, "Interrupted Connection", e);
                Thread.currentThread().interrupt();
            }
        }
    }

    private void playGame() {
        // Remove setup panel
        Platform.runLater(() -> {
            BoardScene.getRootPane().getChildren().remove(BoardScene.getSetupPanel());
        });

        // Get game status from the server
        try {
            edu.asu.stratego.game.Game.setStatus((edu.asu.stratego.game.GameStatus) fromServer.readObject());
        } catch (ClassNotFoundException | IOException e1) {
            LOGGER.log(Level.WARNING, "Server not found", e1);
        }


        // Main loop (when playing)
        while (edu.asu.stratego.game.Game.getStatus() == edu.asu.stratego.game.GameStatus.IN_PROGRESS) {
            try {
                // Get turn color from server.
                edu.asu.stratego.game.Game.setTurn((edu.asu.stratego.game.PieceColor) fromServer.readObject());

                // If the turn is the client's, set move status to none selected
                if (edu.asu.stratego.game.Game.getPlayer().getColor() == edu.asu.stratego.game.Game.getTurn())
                    edu.asu.stratego.game.Game.setMoveStatus(edu.asu.stratego.game.MoveStatus.NONE_SELECTED);
                else
                    edu.asu.stratego.game.Game.setMoveStatus(edu.asu.stratego.game.MoveStatus.OPP_TURN);

                // Notify turn indicator.
                synchronized (BoardTurnIndicator.getTurnIndicatorTrigger()) {
                    BoardTurnIndicator.getTurnIndicatorTrigger().notify();
                }

                // Send move to the server.
                if (edu.asu.stratego.game.Game.getPlayer().getColor() == edu.asu.stratego.game.Game.getTurn() && edu.asu.stratego.game.Game.getMoveStatus() != edu.asu.stratego.game.MoveStatus.SERVER_VALIDATION) {
                    synchronized (sendMove) {
                        sendMove.wait();
                        toServer.writeObject(edu.asu.stratego.game.Game.getMove());
                        edu.asu.stratego.game.Game.setMoveStatus(edu.asu.stratego.game.MoveStatus.SERVER_VALIDATION);
                    }
                }

                // Receive move from the server.
                edu.asu.stratego.game.Game.setMove((edu.asu.stratego.game.Move) fromServer.readObject());
                edu.asu.stratego.game.Piece startPiece = edu.asu.stratego.game.Game.getMove().getStartPiece();
                edu.asu.stratego.game.Piece endPiece = edu.asu.stratego.game.Game.getMove().getEndPiece();

                // If the move is an attack, not just a move to an unoccupied square
                if (edu.asu.stratego.game.Game.getMove().isAttackMove()) {
                    attackMove();
                }

                // Set the piece on the software (non-GUI) board to the updated pieces (either null or the winning piece)
                edu.asu.stratego.game.Game.getBoard().getSquare(edu.asu.stratego.game.Game.getMove().getStart().x, edu.asu.stratego.game.Game.getMove().getStart().y).setPiece(startPiece);
                edu.asu.stratego.game.Game.getBoard().getSquare(edu.asu.stratego.game.Game.getMove().getEnd().x, edu.asu.stratego.game.Game.getMove().getEnd().y).setPiece(endPiece);

                // Update GUI.
                Platform.runLater(() -> {

                    ClientSquare endSquare = edu.asu.stratego.game.Game.getBoard().getSquare(edu.asu.stratego.game.Game.getMove().getEnd().x, edu.asu.stratego.game.Game.getMove().getEnd().y);
                    // Draw
                    if (endPiece == null)
                        endSquare.getPiecePane().setPiece(null);
                    else {
                        // If not a draw, set the end piece to the PieceType face
                        if (endPiece.getPieceColor() == edu.asu.stratego.game.Game.getPlayer().getColor()) {
                            endSquare.getPiecePane().setPiece(HashTables.PIECE_MAP.get(endPiece.getPieceSpriteKey()));
                        }
                        // ...unless it is the opponent's piece which it will display the back instead
                        else {
                            if (endPiece.getPieceColor() == edu.asu.stratego.game.PieceColor.BLUE)
                                endSquare.getPiecePane().setPiece(ImageConstants.BLUE_BACK);
                            else
                                endSquare.getPiecePane().setPiece(ImageConstants.RED_BACK);
                        }
                    }
                });

                // If it is an attack, wait 0.05 seconds to allow the arrow to be visible
                if (edu.asu.stratego.game.Game.getMove().isAttackMove()) {
                    Thread.sleep(50);
                }

                Platform.runLater(() -> {
                    // Arrow

                    ClientSquare arrowSquare = edu.asu.stratego.game.Game.getBoard().getSquare(edu.asu.stratego.game.Game.getMove().getStart().x, edu.asu.stratego.game.Game.getMove().getStart().y);

                    // Change the arrow to an image (and depending on what color the arrow should be)
                    if (edu.asu.stratego.game.Game.getMove().getMoveColor() == edu.asu.stratego.game.PieceColor.RED)
                        arrowSquare.getPiecePane().setPiece(ImageConstants.MOVEARROW_RED);
                    else
                        arrowSquare.getPiecePane().setPiece(ImageConstants.MOVEARROW_BLUE);

                    // Rotate the arrow to show the direction of the move
                    if (edu.asu.stratego.game.Game.getMove().getStart().x > edu.asu.stratego.game.Game.getMove().getEnd().x)
                        arrowSquare.getPiecePane().getPiece().setRotate(0);
                    else if (edu.asu.stratego.game.Game.getMove().getStart().y < edu.asu.stratego.game.Game.getMove().getEnd().y)
                        arrowSquare.getPiecePane().getPiece().setRotate(90);
                    else if (edu.asu.stratego.game.Game.getMove().getStart().x < edu.asu.stratego.game.Game.getMove().getEnd().x)
                        arrowSquare.getPiecePane().getPiece().setRotate(180);
                    else
                        arrowSquare.getPiecePane().getPiece().setRotate(270);

                    // Fade out the arrow
                    FadeTransition ft = new FadeTransition(Duration.millis(1500), arrowSquare.getPiecePane().getPiece());
                    ft.setFromValue(1.0);
                    ft.setToValue(0.0);
                    ft.play();
                    ft.setOnFinished(new ResetSquareImage());
                });

                // Wait for fade animation to complete before continuing.
                synchronized (waitFade) {
                    waitFade.wait();
                }

                // Get game status from server.
                edu.asu.stratego.game.Game.setStatus((edu.asu.stratego.game.GameStatus) fromServer.readObject());
            } catch (ClassNotFoundException | IOException | InterruptedException e) {
                LOGGER.log(Level.WARNING, "Interrupted Connection", e);
                Thread.currentThread().interrupt();
            }
        }
        revealAll();
    }

    private void attackMove() throws InterruptedException {
        edu.asu.stratego.game.Piece attackingPiece = edu.asu.stratego.game.Game.getBoard().getSquare(edu.asu.stratego.game.Game.getMove().getStart().x, edu.asu.stratego.game.Game.getMove().getStart().y).getPiece();
        if (attackingPiece.getPieceType() == edu.asu.stratego.game.PieceType.SCOUT) {
            // Check if the scout is attacking over more than one square
            int moveX = edu.asu.stratego.game.Game.getMove().getStart().x - edu.asu.stratego.game.Game.getMove().getEnd().x;
            int moveY = edu.asu.stratego.game.Game.getMove().getStart().y - edu.asu.stratego.game.Game.getMove().getEnd().y;

            if (Math.abs(moveX) > 1 || Math.abs(moveY) > 1) {
                Platform.runLater(() -> {
                    try {
                        int shiftX = 0;
                        int shiftY = 0;

                        if (moveX > 0) {
                            shiftX = 1;
                        } else if (moveX < 0) {
                            shiftX = -1;
                        } else if (moveY > 0) {
                            shiftY = 1;
                        } else if (moveY < 0) {
                            shiftY = -1;
                        }

                        // Move the scout in front of the piece it's attacking before actually fading out
                        ClientSquare scoutSquare = edu.asu.stratego.game.Game.getBoard().getSquare(edu.asu.stratego.game.Game.getMove().getEnd().x + shiftX, edu.asu.stratego.game.Game.getMove().getEnd().y + shiftY);
                        ClientSquare startSquare = edu.asu.stratego.game.Game.getBoard().getSquare(edu.asu.stratego.game.Game.getMove().getStart().x, edu.asu.stratego.game.Game.getMove().getStart().y);
                        scoutSquare.getPiecePane().setPiece(HashTables.PIECE_MAP.get(scoutSquare.getPiece().getPieceSpriteKey()));
                        startSquare.getPiecePane().setPiece(null);
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Movement not allowed", e);
                    }
                });

                // Wait 1 second after moving the scout in front of the piece it's going to attack
                Thread.sleep(1000);

                int shiftX = 0;
                int shiftY = 0;

                if (moveX > 0) {
                    shiftX = 1;
                } else if (moveX < 0) {
                    shiftX = -1;
                } else if (moveY > 0) {
                    shiftY = 1;
                } else if (moveY < 0) {
                    shiftY = -1;
                }

                ClientSquare startSquare = edu.asu.stratego.game.Game.getBoard().getSquare(edu.asu.stratego.game.Game.getMove().getStart().x, edu.asu.stratego.game.Game.getMove().getStart().y);

                // Fix the clientside software boards (and move) to reflect new scout location, now attacks like a normal piece
                edu.asu.stratego.game.Game.getBoard().getSquare(edu.asu.stratego.game.Game.getMove().getEnd().x + shiftX, edu.asu.stratego.game.Game.getMove().getEnd().y + shiftY).setPiece(startSquare.getPiece());
                edu.asu.stratego.game.Game.getBoard().getSquare(edu.asu.stratego.game.Game.getMove().getStart().x, edu.asu.stratego.game.Game.getMove().getStart().y).setPiece(null);

                edu.asu.stratego.game.Game.getMove().setStart(edu.asu.stratego.game.Game.getMove().getEnd().x + shiftX, edu.asu.stratego.game.Game.getMove().getEnd().y + shiftY);
            }
        }
        Platform.runLater(() -> {
            try {
                // Set the face images visible to both players (from the back that doesn't show piecetype)

                ClientSquare startSquare = edu.asu.stratego.game.Game.getBoard().getSquare(edu.asu.stratego.game.Game.getMove().getStart().x, edu.asu.stratego.game.Game.getMove().getStart().y);
                ClientSquare endSquare = edu.asu.stratego.game.Game.getBoard().getSquare(edu.asu.stratego.game.Game.getMove().getEnd().x, edu.asu.stratego.game.Game.getMove().getEnd().y);

                edu.asu.stratego.game.Piece animStartPiece = startSquare.getPiece();
                edu.asu.stratego.game.Piece animEndPiece = endSquare.getPiece();

                startSquare.getPiecePane().setPiece(HashTables.PIECE_MAP.get(animStartPiece.getPieceSpriteKey()));
                endSquare.getPiecePane().setPiece(HashTables.PIECE_MAP.get(animEndPiece.getPieceSpriteKey()));
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Piece not loaded", e);
            }
        });

        // Wait three seconds (the image is shown to client, then waits 2 seconds)
        Thread.sleep(2000);

        // Fade out pieces that lose (or draw)
        Platform.runLater(() -> {
            try {

                ClientSquare startSquare = edu.asu.stratego.game.Game.getBoard().getSquare(edu.asu.stratego.game.Game.getMove().getStart().x, edu.asu.stratego.game.Game.getMove().getStart().y);
                ClientSquare endSquare = edu.asu.stratego.game.Game.getBoard().getSquare(edu.asu.stratego.game.Game.getMove().getEnd().x, edu.asu.stratego.game.Game.getMove().getEnd().y);

                // If the piece dies, fade it out (also considers a draw, where both "win" are set to false)
                if (!edu.asu.stratego.game.Game.getMove().isAttackWin()) {
                    winnerMove(startSquare);
                }
                if (!edu.asu.stratego.game.Game.getMove().isDefendWin()) {
                    winnerMove(endSquare);
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Fight error", e);
            }
        });

        // Wait 1.5 seconds while the image fades out
        Thread.sleep(1500);
    }

    private void winnerMove(ClientSquare startSquare) {
        FadeTransition fade = new FadeTransition(Duration.millis(1500), startSquare.getPiecePane().getPiece());
        fade.setFromValue(1.0);
        fade.setToValue(0.0);
        fade.play();
        fade.setOnFinished(new ResetImageVisibility());
    }

    public static Object getSendMove() {
        return sendMove;
    }


    private void revealAll() {
        // End game, reveal all pieces
        Platform.runLater(() -> {
            for (int row = 0; row < 10; row++) {
                for (int col = 0; col < 10; col++) {
                    if (edu.asu.stratego.game.Game.getBoard().getSquare(row, col).getPiece() != null && edu.asu.stratego.game.Game.getBoard().getSquare(row, col).getPiece().getPieceColor() != edu.asu.stratego.game.Game.getPlayer().getColor()) {
                        edu.asu.stratego.game.Game.getBoard().getSquare(row, col).getPiecePane().setPiece(HashTables.PIECE_MAP.get(edu.asu.stratego.game.Game.getBoard().getSquare(row, col).getPiece().getPieceSpriteKey()));
                    }
                }
            }
        });
    }

    // Finicky, ill-advised to edit. Resets the opacity, rotation, and piece to null
    // Duplicate "ResetImageVisibility" class was intended to not set piece to null, untested though.

    private class ResetSquareImage implements EventHandler<ActionEvent> {
        @Override
        public void handle(ActionEvent event) {
            synchronized (waitFade) {
                waitFade.notify();
                startEnd();
            }
        }
    }

    // read above comments
    private class ResetImageVisibility implements EventHandler<ActionEvent> {
        @Override
        public void handle(ActionEvent event) {
            synchronized (waitVisible) {
                waitVisible.notify();
                startEnd();
            }
        }
    }

    private void startEnd (){
        edu.asu.stratego.game.Game.getBoard().getSquare(edu.asu.stratego.game.Game.getMove().getStart().x, edu.asu.stratego.game.Game.getMove().getStart().y).getPiecePane().getPiece().setOpacity(1.0);
        edu.asu.stratego.game.Game.getBoard().getSquare(edu.asu.stratego.game.Game.getMove().getStart().x, edu.asu.stratego.game.Game.getMove().getStart().y).getPiecePane().getPiece().setRotate(0.0);
        edu.asu.stratego.game.Game.getBoard().getSquare(edu.asu.stratego.game.Game.getMove().getStart().x, edu.asu.stratego.game.Game.getMove().getStart().y).getPiecePane().setPiece(null);

        edu.asu.stratego.game.Game.getBoard().getSquare(edu.asu.stratego.game.Game.getMove().getEnd().x, edu.asu.stratego.game.Game.getMove().getEnd().y).getPiecePane().getPiece().setOpacity(1.0);
        edu.asu.stratego.game.Game.getBoard().getSquare(edu.asu.stratego.game.Game.getMove().getEnd().x, edu.asu.stratego.game.Game.getMove().getEnd().y).getPiecePane().getPiece().setRotate(0.0);
    }

}