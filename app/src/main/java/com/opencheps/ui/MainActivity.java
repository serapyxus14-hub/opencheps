package com.opencheps.ui;

import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveList;
import com.github.bhlangonijr.chesslib.game.Game;
import com.github.bhlangonijr.chesslib.pgn.PgnHolder;

import com.opencheps.R;
import com.opencheps.engine.StockfishEngine;
import com.opencheps.engine.StockfishEngine.Pv;
import com.opencheps.util.Prefs;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private TextView statusTv;
    private TextView gameTv;
    private TextView positionTv;
    private TextView fenTv;
    private TextView analysisTv;

    private Button importBtn;
    private Button previousBtn;
    private Button nextBtn;
    private Button analyzeBtn;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private StockfishEngine engine;
    private boolean engineReady = false;

    private final List<PositionEntry> positions = new ArrayList<>();
    private int currentPosition = 0;

    private static class PositionEntry {
        final String label;
        final String fen;

        PositionEntry(String label, String fen) {
            this.label = label;
            this.fen = fen;
        }
    }

    private final ActivityResultLauncher<String[]> pgnPicker =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    this::onPgnSelected
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusTv = findViewById(R.id.status);
        gameTv = findViewById(R.id.gameInfo);
        positionTv = findViewById(R.id.positionInfo);
        fenTv = findViewById(R.id.fenInfo);
        analysisTv = findViewById(R.id.analysisInfo);

        importBtn = findViewById(R.id.btnImport);
        previousBtn = findViewById(R.id.btnPrevious);
        nextBtn = findViewById(R.id.btnNext);
        analyzeBtn = findViewById(R.id.btnAnalyze);

        importBtn.setOnClickListener(v -> openPgnPicker());
        previousBtn.setOnClickListener(v -> changePosition(-1));
        nextBtn.setOnClickListener(v -> changePosition(1));
        analyzeBtn.setOnClickListener(v -> analyzeCurrentPosition());

        updatePositionDisplay();
        startEngine();
    }

    private void startEngine() {
        statusTv.setText("Starting Stockfish...");

        executor.execute(() -> {
            try {
                engine = new StockfishEngine();
                engine.start(getApplicationContext());

                runOnUiThread(() -> {
                    engineReady = true;
                    statusTv.setText("Stockfish ready · Import a completed game");
                    updatePositionDisplay();
                });

            } catch (Exception e) {
                android.util.Log.e("OpenCheps", "Engine startup failed", e);

                runOnUiThread(() -> {
                    statusTv.setText("Engine error: " + e.getMessage());
                    Toast.makeText(
                            this,
                            "Stockfish could not start",
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        });
    }

    private void openPgnPicker() {
        pgnPicker.launch(new String[]{
                "application/x-chess-pgn",
                "text/plain",
                "application/octet-stream"
        });
    }

    private void onPgnSelected(Uri uri) {
        if (uri == null) return;

        statusTv.setText("Reading PGN...");
        importBtn.setEnabled(false);

        executor.execute(() -> {
            try {
                String pgn = readTextFile(uri);
                loadPgnGame(pgn);

                runOnUiThread(() -> {
                    importBtn.setEnabled(true);
                    analysisTv.setText("Select a position and press Analyze.");
                    statusTv.setText("Game imported successfully");
                    updatePositionDisplay();
                });

            } catch (Exception e) {
                android.util.Log.e("OpenCheps", "PGN import failed", e);

                runOnUiThread(() -> {
                    importBtn.setEnabled(true);
                    statusTv.setText("Could not import game");
                    Toast.makeText(
                            this,
                            "PGN error: " + e.getMessage(),
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        });
    }

    private String readTextFile(Uri uri) throws Exception {
        StringBuilder result = new StringBuilder();

        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new Exception("Could not open selected file");
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8))) {

                String line;

                while ((line = reader.readLine()) != null) {
                    result.append(line).append('\n');
                }
            }
        }

        return result.toString();
    }

    private void loadPgnGame(String pgnText) throws Exception {
        PgnHolder holder = new PgnHolder();
        holder.loadPgn(pgnText);

        if (holder.getGames().isEmpty()) {
            throw new Exception("No chess game found in this PGN");
        }

        // This first version imports the first game in the file.
        Game game = holder.getGames().get(0);
        game.loadMoveText();

        MoveList moves = game.getHalfMoves();
        String[] sanMoves = moves.toSanArray();

        if (moves.isEmpty()) {
            throw new Exception("The PGN contains no moves");
        }

        Board board = new Board();
        board.loadFromFen(moves.getStartFen());

        List<PositionEntry> loaded = new ArrayList<>();
        loaded.add(new PositionEntry("Starting position", board.getFen()));

        for (int i = 0; i < moves.size(); i++) {
            Move move = moves.get(i);

            if (!board.doMove(move, true)) {
                throw new Exception("Illegal move at position " + (i + 1));
            }

            int moveNumber = (i / 2) + 1;
            String side = (i % 2 == 0) ? ". " : "... ";

            String label = moveNumber + side + sanMoves[i];

            loaded.add(new PositionEntry(label, board.getFen()));
        }

        synchronized (positions) {
            positions.clear();
            positions.addAll(loaded);
            currentPosition = 0;
        }

        String white = game.getWhitePlayer() == null
                ? "White" : game.getWhitePlayer().getName();

        String black = game.getBlackPlayer() == null
                ? "Black" : game.getBlackPlayer().getName();

        String result = game.getResult() == null
                ? "Unknown result" : game.getResult().toString();

        String info = white + " vs " + black + "\nResult: " + result
                + "\nMoves: " + moves.size();

        runOnUiThread(() -> gameTv.setText(info));
    }

    private void changePosition(int direction) {
        synchronized (positions) {
            if (positions.isEmpty()) return;

            currentPosition += direction;

            if (currentPosition < 0) currentPosition = 0;

            if (currentPosition >= positions.size()) {
                currentPosition = positions.size() - 1;
            }
        }

        analysisTv.setText("Select Analyze to review this position.");
        updatePositionDisplay();
    }

    private void updatePositionDisplay() {
        PositionEntry entry;

        synchronized (positions) {
            if (positions.isEmpty()) {
                positionTv.setText("No game loaded");
                fenTv.setText("");
                previousBtn.setEnabled(false);
                nextBtn.setEnabled(false);
                analyzeBtn.setEnabled(false);
                return;
            }

            entry = positions.get(currentPosition);

            positionTv.setText(
                    "Position " + currentPosition + " / "
                            + (positions.size() - 1)
                            + "\n" + entry.label
            );

            fenTv.setText("FEN:\n" + entry.fen);

            previousBtn.setEnabled(currentPosition > 0);
            nextBtn.setEnabled(currentPosition < positions.size() - 1);
            analyzeBtn.setEnabled(engineReady);
        }
    }

    private void analyzeCurrentPosition() {
        if (!engineReady || engine == null) {
            Toast.makeText(this, "Stockfish is not ready", Toast.LENGTH_SHORT).show();
            return;
        }

        final PositionEntry entry;

        synchronized (positions) {
            if (positions.isEmpty()) return;
            entry = positions.get(currentPosition);
        }

        analyzeBtn.setEnabled(false);
        statusTv.setText("Analyzing completed-game position...");
        analysisTv.setText("Stockfish is thinking...");

        executor.execute(() -> {
            try {
                int thinkMs = Math.max(
                        300,
                        Prefs.thinkMs(getApplicationContext())
                );

                int multiPv = Math.max(
                        1,
                        Math.min(3, Prefs.multiPv(getApplicationContext()))
                );

                List<Pv> results =
                        engine.topMoves(entry.fen, thinkMs, multiPv);

                StringBuilder output = new StringBuilder();

                if (results.isEmpty()) {
                    output.append("No legal moves found.");
                } else {
                    output.append("STOCKFISH ANALYSIS\n\n");

                    for (int i = 0; i < results.size(); i++) {
                        Pv pv = results.get(i);

                        output.append(i + 1)
                                .append(". ")
                                .append(pv.uci)
                                .append("\nEvaluation: ");

                        if (pv.mate) {
                            output.append("Mate ")
                                    .append(Math.abs(pv.cp));
                        } else {
                            output.append(String.format(
                                    java.util.Locale.US,
                                    "%+.2f",
                                    pv.cp / 100.0
                            ));
                        }

                        output.append("\n\n");
                    }
                }

                String finalOutput = output.toString();

                runOnUiThread(() -> {
                    analysisTv.setText(finalOutput);
                    statusTv.setText("Analysis complete");
                    analyzeBtn.setEnabled(true);
                });

            } catch (Exception e) {
                android.util.Log.e("OpenCheps", "Analysis failed", e);

                runOnUiThread(() -> {
                    analysisTv.setText("Analysis failed:\n" + e.getMessage());
                    statusTv.setText("Analysis error");
                    analyzeBtn.setEnabled(true);
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        executor.execute(() -> {
            try {
                if (engine != null) engine.stop();
            } catch (Exception ignored) {
            }
        });

        executor.shutdown();
        super.onDestroy();
    }
}
