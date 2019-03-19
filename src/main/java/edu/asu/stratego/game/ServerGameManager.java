package edu.asu.stratego.game;

import java.awt.Point;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.logging.Level;

import edu.asu.stratego.game.board.ServerBoard;

import static com.sun.xml.internal.ws.spi.db.BindingContextFactory.LOGGER;

/**
 * Task to manage a Stratego game between two clients.
 */
public class ServerGameManager implements Runnable {

	private final String session;

	private ServerBoard board = new ServerBoard();

	private ObjectOutputStream toPlayerOne;
	private ObjectOutputStream toPlayerTwo;
	private ObjectInputStream fromPlayerOne;
	private ObjectInputStream fromPlayerTwo;

	private Player playerOne = new Player();
	private Player playerTwo = new Player();

	private Point playerOneFlag;
	private Point playerTwoFlag;

	private PieceColor turn;
	private Move move;

	private Socket socketOne;
	private Socket socketTwo;

	/**
	 * Creates a new instance of ServerGameManager.
	 *
	 * @param sockOne    socket connected to Player 1's client
	 * @param sockTwo    socket connected to Player 2's client
	 * @param sessionNum the nth game session created by Server.
	 * @see edu.asu.stratego.Server
	 */
	public ServerGameManager(Socket sockOne, Socket sockTwo, int sessionNum) {
		this.session = "Session " + sessionNum + ": ";
		this.socketOne = sockOne;
		this.socketTwo = sockTwo;

		if (Math.random() < 0.5)
			this.turn = PieceColor.RED;
		else
			this.turn = PieceColor.BLUE;
	}

	/**
	 * See ClientGameManager's run() method to understand how the server
	 * interacts with the client.
	 *
	 * @see ClientGameManager
	 */
	@Override
	public void run() {
		createIOStreams();
		exchangePlayers();
		exchangeSetup();

		playGame();
	}

	/**
	 * Establish IO object streams to facilitate communication between the
	 * client and server.
	 */
	private void createIOStreams() {
		try {
			// NOTE: ObjectOutputStreams must be constructed before the
			//       ObjectInputStreams so as to prevent a remote deadlock.
			toPlayerOne = new ObjectOutputStream(socketOne.getOutputStream());
			fromPlayerOne = new ObjectInputStream(socketOne.getInputStream());
			toPlayerTwo = new ObjectOutputStream(socketTwo.getOutputStream());
			fromPlayerTwo = new ObjectInputStream(socketTwo.getInputStream());
		} catch (IOException e) {
			LOGGER.log(Level.INFO, e.getMessage(), e.getCause());
		}
	}

	/**
	 * Receive player information from the clients. Determines the players'
	 * colors, and sends the player information of the opponents back to the
	 * clients.
	 */
	private void exchangePlayers() {
		try {
			playerOne = (Player) fromPlayerOne.readObject();
			playerTwo = (Player) fromPlayerTwo.readObject();


			if (Math.random() < 0.5) {
				playerOne.setColor(PieceColor.RED);
				playerTwo.setColor(PieceColor.BLUE);
			} else {
				playerOne.setColor(PieceColor.BLUE);
				playerTwo.setColor(PieceColor.RED);
			}

			toPlayerOne.writeObject(playerTwo);
			toPlayerTwo.writeObject(playerOne);
		} catch (ClassNotFoundException | IOException e) {
			LOGGER.log(Level.INFO, e.getMessage(), e.getCause());
		}
	}

	private void exchangeSetup() {
		try {
			SetupBoard setupBoardOne = (SetupBoard) fromPlayerOne.readObject();
			SetupBoard setupBoardTwo = (SetupBoard) fromPlayerTwo.readObject();

			// Register pieces on the server board.
			for (int row = 0; row < 4; ++row) {
				for (int col = 0; col < 10; ++col) {
					board.getSquare(row, col).setPiece(setupBoardOne.getPiece(3 - row, 9 - col));
					board.getSquare(row + 6, col).setPiece(setupBoardTwo.getPiece(row, col));

					if (setupBoardOne.getPiece(3 - row, 9 - col).getPieceType() == PieceType.FLAG)
						playerOneFlag = new Point(row, col);
					if (setupBoardTwo.getPiece(row, col).getPieceType() == PieceType.FLAG)
						playerTwoFlag = new Point(row + 6, col);
				}
			}

			// Rotate pieces by 180 degrees.
			for (int row = 0; row < 2; ++row) {
				for (int col = 0; col < 10; ++col) {
					// Player One
					Piece temp = setupBoardOne.getPiece(row, col);
					setupBoardOne.setPiece(setupBoardOne.getPiece(3 - row, 9 - col), row, col);
					setupBoardOne.setPiece(temp, 3 - row, 9 - col);

					// Player Two
					temp = setupBoardTwo.getPiece(row, col);
					setupBoardTwo.setPiece(setupBoardTwo.getPiece(3 - row, 9 - col), row, col);
					setupBoardTwo.setPiece(temp, 3 - row, 9 - col);
				}
			}
			updatePlayers(setupBoardTwo, setupBoardOne);
		} catch (ClassNotFoundException | IOException e) {
			LOGGER.log(Level.INFO, e.getMessage(), e.getCause());
		}

	}

	private void playGame() {
		boolean playactive=true;
		while (playactive) {
			try {
				// Send player turn color to clients.
				toPlayerOne.writeObject(turn);
				toPlayerTwo.writeObject(turn);

				// Get turn from client.
				if (playerOne.getColor() == turn) {
					move = (Move) fromPlayerOne.readObject();
					move.setStart(9 - move.getStart().x, 9 - move.getStart().y);
					move.setEnd(9 - move.getEnd().x, 9 - move.getEnd().y);
				} else {
					move = (Move) fromPlayerTwo.readObject();
				}

				Move moveToPlayerOne = new Move();
				Move moveToPlayerTwo = new Move();

				// Register move on the board.
				// If there is no piece at the end (normal move, no attack)
				if (board.getSquare(move.getEnd().x, move.getEnd().y).getPiece() == null) {
					Piece piece = board.getSquare(move.getStart().x, move.getStart().y).getPiece();

					board.getSquare(move.getStart().x, move.getStart().y).setPiece(null);
					board.getSquare(move.getEnd().x, move.getEnd().y).setPiece(piece);

					// Rotate the move 180 degrees before sending.
					movePlayerOne(moveToPlayerOne, piece);
					movePlayerTwo(moveToPlayerTwo, piece);
				} else {
					Piece attackingPiece = board.getSquare(move.getStart().x, move.getStart().y).getPiece();
					Piece defendingPiece = board.getSquare(move.getEnd().x, move.getEnd().y).getPiece();

					BattleOutcome outcome = attackingPiece.getPieceType().attack(defendingPiece.getPieceType());

					moveToPlayerOne.setAttackMove(true);
					moveToPlayerTwo.setAttackMove(true);

					if (outcome == BattleOutcome.WIN) {
						board.getSquare(move.getEnd().x, move.getEnd().y).setPiece(board.getSquare(move.getStart().x, move.getStart().y).getPiece());
						board.getSquare(move.getStart().x, move.getStart().y).setPiece(null);

						// Rotate the move 180 degrees before sending.
						movePlayerOne(moveToPlayerOne, attackingPiece);
						moveToPlayerOne.setAttackWin(true);
						moveToPlayerOne.setDefendWin(false);

						movePlayerTwo(moveToPlayerTwo, attackingPiece);
						moveToPlayerTwo.setAttackWin(true);
						moveToPlayerTwo.setDefendWin(false);
					} else if (outcome == BattleOutcome.LOSE) {
						board.getSquare(move.getStart().x, move.getStart().y).setPiece(null);

						// Rotate the move 180 degrees before sending.
						movePlayerOne(moveToPlayerOne, defendingPiece);
						moveToPlayerOne.setAttackWin(false);
						moveToPlayerOne.setDefendWin(true);

						movePlayerTwo(moveToPlayerTwo, defendingPiece);
						moveToPlayerTwo.setAttackWin(false);
						moveToPlayerTwo.setDefendWin(true);
					} else if (outcome == BattleOutcome.DRAW) {
						board.getSquare(move.getStart().x, move.getStart().y).setPiece(null);
						board.getSquare(move.getEnd().x, move.getEnd().y).setPiece(null);

						// Rotate the move 180 degrees before sending.
						setMove(moveToPlayerOne, 9 - move.getStart().x, 9 - move.getStart().y, 9 - move.getEnd().x, 9 - move.getEnd().y);
						moveToPlayerOne.setEndPiece(null);
						moveToPlayerOne.setAttackWin(false);
						moveToPlayerOne.setDefendWin(false);

						setMove(moveToPlayerOne, move.getStart().x, move.getStart().y, move.getEnd().x, move.getEnd().y);
						moveToPlayerTwo.setEndPiece(null);
						moveToPlayerTwo.setAttackWin(false);
						moveToPlayerTwo.setDefendWin(false);
					}
				}
				updatePlayers(moveToPlayerOne, moveToPlayerTwo);


				// Change turn color.
				if (turn == PieceColor.RED)
					turn = PieceColor.BLUE;
				else
					turn = PieceColor.RED;

				// Check win conditions.
			} catch (IOException | ClassNotFoundException e) {
				playactive=false;
				LOGGER.log(Level.INFO, "Error occured during network I/O", e.getCause());
			}
		}
	}

	private void updatePlayers(Object toWriteInPlayerOne, Object toWriteInPlayerTwo) throws IOException {
		GameStatus winCondition = checkWinCondition();

		toPlayerOne.writeObject(toWriteInPlayerOne);
		toPlayerTwo.writeObject(toWriteInPlayerTwo);

		toPlayerOne.writeObject(winCondition);
		toPlayerTwo.writeObject(winCondition);
	}

	private void setMove(Move movePlayer, int xStart, int yStart, int xEnd, int yEnd) {
		movePlayer.setStart(new Point(xStart, yStart));
		movePlayer.setEnd(new Point(xEnd, yEnd));
		movePlayer.setMoveColor(move.getMoveColor());
		movePlayer.setStartPiece(null);
	}

	private void movePlayerTwo(Move moveToPlayerTwo, Piece piece) {
		setMove(moveToPlayerTwo, move.getStart().x, move.getStart().y, move.getEnd().x, move.getEnd().y);
		moveToPlayerTwo.setEndPiece(piece);
	}

	private void movePlayerOne(Move moveToPlayerOne, Piece piece) {
		setMove(moveToPlayerOne, 9 - move.getStart().x, 9 - move.getStart().y, 9 - move.getEnd().x, 9 - move.getEnd().y);
		moveToPlayerOne.setEndPiece(piece);
	}

	private GameStatus checkWinCondition() {
		if (unableToMove(PieceColor.RED))
			return GameStatus.RED_NO_MOVES;

		else if (isCaptured(PieceColor.RED))
			return GameStatus.RED_CAPTURED;

		if (unableToMove(PieceColor.BLUE))
			return GameStatus.BLUE_NO_MOVES;

		else if (isCaptured(PieceColor.BLUE))
			return GameStatus.BLUE_CAPTURED;

		return GameStatus.IN_PROGRESS;
	}

	private boolean isCaptured(PieceColor inColor) {
		if ((playerOne.getColor() == inColor) && (board.getSquare(playerOneFlag.x, playerOneFlag.y).getPiece().getPieceType() != PieceType.FLAG)) {
			return true;

		}
		if (playerTwo.getColor() == inColor) {
			return board.getSquare(playerTwoFlag.x, playerTwoFlag.y).getPiece().getPieceType() != PieceType.FLAG;
		}
		return false;
	}

	private boolean unableToMove(PieceColor inColor) {
		for (int row = 0; row < 10; ++row) {
			for (int col = 0; col < 10; ++col) {
				if ((board.getSquare(row, col).getPiece() != null && board.getSquare(row, col).getPiece().getPieceColor() == inColor) && (computeValidMoves(row, col, inColor).size() > 0)) {

					return false;

				}
			}
		}
		return true;
	}

	private ArrayList<Point> computeValidMoves(int row, int col, PieceColor inColor) {
		int max = 1;
		PieceType pieceType = board.getSquare(row, col).getPiece().getPieceType();
		if (pieceType == PieceType.SCOUT)
			max = 8;

		ArrayList<Point> validMoves = new ArrayList<>();

		if (pieceType != PieceType.BOMB && pieceType != PieceType.FLAG) {
			// Negative Row (UP)
			for (int i = -1; i >= -max; --i) {
				if (checkValidRow(row, col, inColor, validMoves, i)) break;
			}
			// Positive Col (RIGHT)
			for (int i = 1; i <= max; ++i) {
				if (checkValidColumn(row, col, inColor, validMoves, i)) break;
			}
			// Positive Row (DOWN)
			for (int i = 1; i <= max; ++i) {
				if (checkValidRow(row, col, inColor, validMoves, i)) break;
			}
			// Negative Col (LEFT)
			for (int i = -1; i >= -max; --i) {
				if (checkValidColumn(row, col, inColor, validMoves, i)) break;
			}
		}

		return validMoves;
	}

	private boolean checkValidColumn(int row, int col, PieceColor inColor, ArrayList<Point> validMoves, int i) {
		if (isInBounds(row, col + i) && (isGround(row, col + i) || (!isNullPiece(row, col + i) && !isOpponentPiece(row, col + i, inColor)))) {
			if (isNullPiece(row, col + i) || isOpponentPiece(row, col + i, inColor)) {
				validMoves.add(new Point(row, col + i));
				return !isNullPiece(row, col + i) && isOpponentPiece(row, col + i, inColor);
			} else
				return true;
		} else
			return true;
	}

	private boolean checkValidRow(int row, int col, PieceColor inColor, ArrayList<Point> validMoves, int i) {
		if (isInBounds(row + i, col) && (isGround(row + i, col) || (!isNullPiece(row + i, col) && !isOpponentPiece(row + i, col, inColor)))) {
			if (isNullPiece(row + i, col) || isOpponentPiece(row + i, col, inColor)) {
				validMoves.add(new Point(row + i, col));
				return !isNullPiece(row + i, col) && isOpponentPiece(row + i, col, inColor);
			} else
				return true;
		} else
			return true;
	}

	private static boolean isGround(int row, int col) {
		if (col == 2 || col == 3 || col == 6 || col == 7) {
			return row != 4 && row != 5;
		}
		return true;
	}

	private static boolean isInBounds(int row, int col) {
		if (row < 0 || row > 9)
			return false;
		return col >= 0 && col <= 9;
	}

	private boolean isOpponentPiece(int row, int col, PieceColor inColor) {
		return board.getSquare(row, col).getPiece().getPieceColor() != inColor;
	}

	private boolean isNullPiece(int row, int col) {
		return board.getSquare(row, col).getPiece() == null;
	}
}