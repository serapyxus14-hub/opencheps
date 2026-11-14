
package com.opencheps.capture;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.opencheps.R;
import com.opencheps.engine.StockfishEngine;
import com.opencheps.overlay.ArrowOverlayView;
import com.opencheps.overlay.FloatingPanel;
import com.opencheps.overlay.OverlayService;
import com.opencheps.util.Prefs;
import com.opencheps.util.Side;
import com.opencheps.vision.BoardDetector;
import com.opencheps.vision.FenBuilder;
import com.opencheps.vision.MoveAnalyzer;
import com.opencheps.vision.PieceClassifier;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ScreenCaptureService extends Service {

    private static final String TAG = "OC/Capture";
    private static final String CHANNEL_ID = "opencheps_capture";

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_USER_SIDE = "user_side";
    public static final String ACTION_STOP = "com.opencheps.STOP";

    private static final int[] ARROW_COLORS = {
            0xFF00C853, 0xFFFFB300, 0xFF29B6F6
    };

    private static final float[] ARROW_WIDTHS = {
            18f, 13f, 10f
    };

    private static final long SAMPLE_PERIOD_MS = 500;
    private static final int LOST_BOARD_THRESHOLD = 8;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private HandlerThread workerThread;
    private Handler worker;

    private StockfishEngine engine;
    private boolean engineReady;
    private PieceClassifier classifier;

    private int captureW, captureH, density;
    private int screenW, screenH;

    private float capToScreenX = 1f;
    private float capToScreenY = 1f;

    private volatile boolean busy = false;
    private volatile boolean paused = false;

    private Side userSide = Side.WHITE;

    private char[][] confirmedGrid;
    private char[][] candidateGrid;

    private int candidateRepeats;

    private Side observedStm = Side.WHITE;

    private String lastEngineFen = "";
    private String lastDisplayedArrowFen = "";

    private int lostBoardCount = 0;

    private BoardDetector.Board lastKnownBoard;

    private List<ArrowOverlayView.Arrow> currentArrows =
            new ArrayList<>();

    @Nullable
    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startInForeground();

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);

        Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);

        String sideStr = intent.getStringExtra(EXTRA_USER_SIDE);

        if (sideStr == null) {
            sideStr = Prefs.userSide(this);
        }

        userSide = "b".equals(sideStr)
                ? Side.BLACK
                : Side.WHITE;

        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(
                        MEDIA_PROJECTION_SERVICE
                );

        projection = mpm.getMediaProjection(resultCode, data);

        if (projection == null) {
            Log.e(TAG, "MediaProjection acquisition failed");
            stopSelf();
            return START_NOT_STICKY;
        }

        projection.registerCallback(
                new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        stopSelf();
                    }
                },
                null
        );

        workerThread = new HandlerThread(
                "oc-worker",
                Thread.NORM_PRIORITY
        );

        workerThread.start();

        worker = new Handler(workerThread.getLooper());

        engine = new StockfishEngine();
        classifier = new PieceClassifier();

        worker.post(() -> {
            try {
                engine.start(getApplicationContext());
                engineReady = true;

                panelStatus(
                        "Engine ready · playing as " + userSide
                );

            } catch (Exception e) {
                Log.e(TAG, "engine start failed", e);
                panelStatus("Engine error: " + e.getMessage());
            }
        });

        setupVirtualDisplay();
        wirePanel();

        worker.postDelayed(sampleLoop, 1000);

        panelStatus("Starting as " + userSide);

        return START_STICKY;
    }

    private void wirePanel() {

        new Handler(getMainLooper()).postDelayed(() -> {

            FloatingPanel p = OverlayService.panel();

            if (p != null) {

                p.placeBottomLeft(screenW, screenH);
                p.setUserSide(userSide);

                p.setListener(new FloatingPanel.Listener() {

                    @Override
                    public void onPauseToggle(boolean isPaused) {

                        paused = isPaused;

                        if (isPaused) {

                            OverlayService.clearAll();
                            panelStatus("Paused");

                        } else {

                            lastEngineFen = "";
                            lastDisplayedArrowFen = "";

                            candidateGrid = null;
                            candidateRepeats = 0;

                            if (worker != null) {
                                worker.post(() -> drainReader());
                            }

                            panelStatus("Resumed");
                        }
                    }

                    @Override
                    public void onStop() {
                        stopSelf();
                    }

                    @Override
                    public void onRecalibrate() {

                        classifier.reset();
                        resetGameState();

                        OverlayService.clearAll();

                        panelStatus(
                                "Recalibrating · show starting position"
                        );
                    }

                    @Override
                    public void onSideChange(Side newSide) {

                        userSide = newSide;

                        Prefs.setUserSide(
                                getApplicationContext(),
                                newSide == Side.BLACK ? "b" : "w"
                        );

                        if (classifier.isTrained()
                                && classifier.bootstrapSide() != newSide) {

                            classifier.reset();
                            resetGameState();

                            OverlayService.clearAll();

                            panelStatus(
                                    "Switched to " + newSide + " · recalibrate"
                            );

                        } else {

                            lastEngineFen = "";
                            lastDisplayedArrowFen = "";

                            observedStm = newSide;

                            panelStatus("Now playing " + newSide);
                        }
                    }
                });
            }

        }, 400);
    }

    private void resetGameState() {

        confirmedGrid = null;
        candidateGrid = null;

        candidateRepeats = 0;

        lastEngineFen = "";
        lastDisplayedArrowFen = "";

        observedStm = Side.WHITE;

        currentArrows.clear();
    }

    private final Runnable sampleLoop = new Runnable() {

        @Override
        public void run() {

            try {

                if (!paused) {
                    sampleOnce();
                } else {
                    drainReader();
                }

            } catch (Throwable t) {

                Log.e(TAG, "sample error", t);
            }

            if (worker != null) {
                worker.postDelayed(this, SAMPLE_PERIOD_MS);
            }
        }
    };

    private void drainReader() {

        if (reader == null) return;

        while (true) {

            Image im;

            try {
                im = reader.acquireNextImage();
            } catch (Exception e) {
                break;
            }

            if (im == null) break;

            im.close();
        }
    }

    private void sampleOnce() {

        if (busy || reader == null) return;

        Image img;

        try {
            img = reader.acquireLatestImage();
        } catch (Exception e) {
            return;
        }

        if (img == null) return;

        busy = true;

        Bitmap bmp = null;

        try {

            bmp = imageToBitmap(img);

            img.close();
            img = null;

            BoardDetector.Board board =
                    BoardDetector.locate(bmp);

            if (board == null) {

                lostBoardCount++;

                if (lostBoardCount < LOST_BOARD_THRESHOLD) {

                    if (lostBoardCount == 1) {
                        panelStatus("Looking for board…");
                    }

                    return;
                }

                panelStatus("Board lost (" + lostBoardCount + ")");

                OverlayService.clearAll();

                return;
            }

            lostBoardCount = 0;

            lastKnownBoard = board;

            Rect screenBounds = boardBoundsOnScreen(board);

            OverlayService.setBoardBounds(screenBounds);

            if (!engineReady) {

                panelStatus("Board found · waiting for engine");

                return;
            }

            if (!classifier.isTrained()) {

                boolean ok = classifier.tryBootstrap(bmp, board);

                if (!ok) {

                    panelStatus(
                            "Show STARTING position to calibrate"
                    );

                    OverlayService.setBoardFrameColor(0x66FFB300);

                    return;
                }

                Side detected = classifier.bootstrapSide();

                if (detected != userSide) {

                    classifier.reset();

                    panelStatus(
                            "Board shows " + detected
                                    + " — flip or change colour"
                    );

                    OverlayService.setBoardFrameColor(0x66D32F2F);

                    return;
                }

                OverlayService.setBoardFrameColor(0x6600C853);

                confirmedGrid = startingGridFor(userSide);

                observedStm = Side.WHITE;

                candidateGrid = null;
                candidateRepeats = 0;

                panelStatus(
                        "Calibrated · you play " + userSide
                );

                if (observedStm == userSide) {
                    queryEngineAndRender(board, bmp);
                } else {
                    panelStatus(observedStm + "'s turn — waiting");
                }

                return;
            }

            char[][] grid =
                    classifier.classifyBoard(bmp, board);

            if (grid == null) return;

            grid = repairWithMoveLogic(confirmedGrid, grid);

            if (gridsEqual(grid, confirmedGrid)) {

                candidateGrid = null;
                candidateRepeats = 0;

                classifier.commitConfirmed(
                        confirmedGrid,
                        bmp,
                        board
                );

                if (observedStm == userSide
                        && !lastEngineFen.equals(fenOf(confirmedGrid))) {

                    queryEngineAndRender(board, bmp);

                } else if (observedStm == userSide) {

                    repushCurrentArrows(board);

                    panelStatus("Your move (ready)");

                } else {

                    OverlayService.clearArrowsOnly();

                    panelStatus(observedStm + "'s turn — waiting");
                }

                return;
            }

            if (!gridsEqual(grid, candidateGrid)) {

                candidateGrid = grid;
                candidateRepeats = 1;

                panelStatus("Saw change…");

                return;
            }

            candidateRepeats++;

            if (candidateRepeats < 2) {

                panelStatus("Saw change… confirming");

                return;
            }

            MoveAnalyzer.Result m =
                    MoveAnalyzer.analyze(confirmedGrid, grid);

            Log.i(
                    TAG,
                    "Move attempt: kind=" + m.kind
                            + " mover=" + m.mover
                            + " changes=" + m.changeCount
            );

            if (m.kind == MoveAnalyzer.Kind.UNKNOWN
                    || m.mover == null) {

                if (candidateRepeats >= 5) {

                    char[][] forced = forceValidGrid(grid);

                    if (forced != null) {

                        confirmedGrid = forced;

                        classifier.commitConfirmed(
                                confirmedGrid,
                                bmp,
                                board
                        );

                        observedStm = userSide;

                        candidateGrid = null;
                        candidateRepeats = 0;

                        panelStatus("Forced re-sync");

                        if (observedStm == userSide) {
                            queryEngineAndRender(board, bmp);
                        }

                    } else {

                        panelStatus(
                                "Position unclear · "
                                        + m.changeCount + " changed"
                        );
                    }

                } else {

                    panelStatus(
                            "Unclear change · "
                                    + m.changeCount + " sq"
                    );
                }

                return;
            }

            FenBuilder.Validation v =
                    FenBuilder.validate(
                            classifier.bootstrapSide() == Side.BLACK
                                    ? FenBuilder.flip(grid)
                                    : grid
                    );

            if (!v.ok) {

                panelStatus(
                        "Position invalid (" + v.reason
                                + ") · keeping previous"
                );

                return;
            }

            confirmedGrid = grid;

            classifier.commitConfirmed(
                    confirmedGrid,
                    bmp,
                    board
            );

            observedStm = m.mover == Side.WHITE
                    ? Side.BLACK
                    : Side.WHITE;

            candidateGrid = null;
            candidateRepeats = 0;

            panelStatus(m.mover + " played " + m.kind);

            if (observedStm == userSide) {

                queryEngineAndRender(board, bmp);

            } else {

                OverlayService.clearArrowsOnly();

                currentArrows.clear();

                lastDisplayedArrowFen = "";

                panelStatus(
                        observedStm + "'s turn — waiting"
                );

                panelEval("");
            }

        } catch (Exception e) {

            Log.w(TAG, "loop iter failed: " + e.getMessage(), e);

        } finally {

            if (bmp != null && !bmp.isRecycled()) {
                bmp.recycle();
            }

            if (img != null) {
                try {
                    img.close();
                } catch (Exception ignored) {
                }
            }

            busy = false;
        }
    }

    private char[][] forceValidGrid(char[][] grid) {

        FenBuilder.Validation v =
                FenBuilder.validate(
                        classifier.bootstrapSide() == Side.BLACK
                                ? FenBuilder.flip(grid)
                                : grid
                );

        return v.ok ? grid : null;
    }

    private void queryEngineAndRender(
            BoardDetector.Board board,
            Bitmap currentBitmap) {

        if (confirmedGrid == null) return;

        FenBuilder.Validation v =
                FenBuilder.validate(
                        classifier.bootstrapSide() == Side.BLACK
                                ? FenBuilder.flip(confirmedGrid)
                                : confirmedGrid
                );

        if (!v.ok) {

            panelStatus("Invalid: " + v.reason);

            return;
        }

        // FIX: Use the actual observed side to move.
        String fen = FenBuilder.build(
                confirmedGrid,
                classifier.bootstrapSide(),
                observedStm
        );

        if (fen == null) {

            panelStatus("FEN build failed");

            return;
        }

        if (fen.equals(lastEngineFen)) {

            repushCurrentArrows(board);

            return;
        }

        lastEngineFen = fen;

        int think = Prefs.thinkMs(getApplicationContext());

        int multiPv = Math.max(
                1,
                Math.min(3, Prefs.multiPv(getApplicationContext()))
        );

        panelStatus("Thinking " + think + " ms…");

        try {

            long t0 = System.currentTimeMillis();

            List<StockfishEngine.Pv> pvs =
                    engine.topMoves(fen, think, multiPv);

            long dt = System.currentTimeMillis() - t0;

            if (pvs.isEmpty()) {

                panelStatus("No legal move");

                return;
            }

            currentArrows = buildArrows(pvs, board);

            lastDisplayedArrowFen = fen;

            OverlayService.pushArrows(
                    currentArrows,
                    boardBoundsOnScreen(board)
            );

            StockfishEngine.Pv best = pvs.get(0);

            String evalStr = best.mate
                    ? "M" + Math.abs(plyToMate(best.cp))
                    : String.format("%+.2f", best.cp / 100.0);

            panelEval(
                    "YOU (" + userSide + ") "
                            + formatUci(best.uci)
                            + "  " + evalStr
            );

            panelStatus("Your move (" + dt + " ms)");

        } catch (Exception e) {

            Log.w(TAG, "engine query failed", e);

            panelStatus("Engine query failed");
        }
    }

    private void repushCurrentArrows(BoardDetector.Board board) {

        if (currentArrows == null || currentArrows.isEmpty()) {
            return;
        }

        List<ArrowOverlayView.Arrow> rebuilt =
                rebuildArrowsForNewBounds(board);

        OverlayService.pushArrows(
                rebuilt,
                boardBoundsOnScreen(board)
        );
    }
