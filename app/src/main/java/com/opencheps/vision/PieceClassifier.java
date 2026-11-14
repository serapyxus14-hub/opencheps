
package com.opencheps.vision;

import android.graphics.Bitmap;
import android.util.Log;

import com.opencheps.util.Side;

import java.util.Arrays;

public class PieceClassifier {

    private static final String TAG = "OC/Classifier";

    private static final char[] START_WHITE_POV = {
        'r','n','b','q','k','b','n','r',
        'p','p','p','p','p','p','p','p',
        '.','.','.','.','.','.','.','.',
        '.','.','.','.','.','.','.','.',
        '.','.','.','.','.','.','.','.',
        '.','.','.','.','.','.','.','.',
        'P','P','P','P','P','P','P','P',
        'R','N','B','Q','K','B','N','R'
    };

    private SquareSampler.Sample[] templates;
    private SquareSampler.Sample[] previousFrame;
    private char[] previousLabels;
    private char[] labels;

    private float pieceColorThreshold;
    private float frameChangeThreshold;
    private float whitePieceLumAvg;
    private float blackPieceLumAvg;

    private boolean trained;
    private Side bootstrapSide;

    public boolean isTrained() {
        return trained;
    }

    public Side bootstrapSide() {
        return bootstrapSide;
    }

    public synchronized boolean tryBootstrap(
            Bitmap bmp,
            BoardDetector.Board board) {

        if (bmp == null || board == null) return false;

        SquareSampler.Sample[] samples = sampleAll(bmp, board);

        if (samples == null) {
            Log.w(TAG, "Bootstrap failed: invalid board samples");
            return false;
        }

        float[] emptyEdges = new float[32];
        float[] occupiedEdges = new float[32];

        int emptyIndex = 0;
        int occupiedIndex = 0;

        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {

                int index = r * 8 + f;
                float edge = samples[index].edgeEnergy;

                if (r >= 2 && r <= 5) {
                    emptyEdges[emptyIndex++] = edge;
                } else {
                    occupiedEdges[occupiedIndex++] = edge;
                }
            }
        }

        Arrays.sort(emptyEdges);
        Arrays.sort(occupiedEdges);

        float emptyMedian = median(emptyEdges);
        float occupiedMedian = median(occupiedEdges);

        float separation = occupiedMedian - emptyMedian;

        if (separation <= 0.0001f) {
            Log.w(TAG,
                    "Bootstrap failed: pieces not distinguishable. "
                    + "empty=" + emptyMedian
                    + " occupied=" + occupiedMedian);
            return false;
        }

        pieceColorThreshold =
                emptyMedian + separation * 0.5f;

        float topLum = 0;
        float bottomLum = 0;

        for (int f = 0; f < 8; f++) {
            topLum += samples[f].meanLum;
            topLum += samples[8 + f].meanLum;

            bottomLum += samples[48 + f].meanLum;
            bottomLum += samples[56 + f].meanLum;
        }

        topLum /= 16f;
        bottomLum /= 16f;

        Side detectedSide =
                bottomLum > topLum ? Side.WHITE : Side.BLACK;

        char[] startingLabels =
                detectedSide == Side.WHITE
                ? START_WHITE_POV.clone()
                : flipLabels(START_WHITE_POV);

        templates = samples;
        labels = startingLabels;
        bootstrapSide = detectedSide;

        float whiteSum = 0;
        float blackSum = 0;

        int whiteCount = 0;
        int blackCount = 0;

        for (int i = 0; i < 64; i++) {

            char piece = labels[i];

            if (piece == '.') continue;

            float lum = samples[i].meanLum;

            if (Character.isUpperCase(piece)) {
                whiteSum += lum;
                whiteCount++;
            } else {
                blackSum += lum;
                blackCount++;
            }
        }

        whitePieceLumAvg =
                whiteCount > 0 ? whiteSum / whiteCount : 0.7f;

        blackPieceLumAvg =
                blackCount > 0 ? blackSum / blackCount : 0.3f;

        previousFrame = samples;
        previousLabels = startingLabels.clone();

        float[] noiseValues = new float[496];
        int noiseCount = 0;

        for (int i = 0; i < 64; i++) {

            if (labels[i] != '.') continue;

            for (int j = i + 1; j < 64; j++) {

                if (labels[j] != '.') continue;

                boolean sameColor =
                        (((i / 8) + (i % 8)) & 1)
                        == (((j / 8) + (j % 8)) & 1);

                if (!sameColor) continue;

                float distance = SquareSampler.lumDistance(
                        samples[i].feature,
                        samples[j].feature
                );

                if (noiseCount < noiseValues.length) {
                    noiseValues[noiseCount++] = distance;
                }
            }
        }

        if (noiseCount > 0) {

            float[] validNoise =
                    Arrays.copyOf(noiseValues, noiseCount);

            Arrays.sort(validNoise);

            int percentileIndex =
                    Math.min(validNoise.length - 1,
                    (int) (validNoise.length * 0.90f));

            float normalNoise = validNoise[percentileIndex];

            frameChangeThreshold =
                    Math.max(0.3f, normalNoise * 1.5f + 0.1f);

        } else {
            frameChangeThreshold = 0.3f;
        }

        trained = true;

        Log.i(TAG,
                "BOOTSTRAPPED"
                + " side=" + detectedSide
                + " emptyMedian=" + emptyMedian
                + " occupiedMedian=" + occupiedMedian
                + " threshold=" + pieceColorThreshold
                + " frameThreshold=" + frameChangeThreshold);

        return true;
    }

    public synchronized char[][] classifyBoard(
            Bitmap bmp,
            BoardDetector.Board board) {

        if (!trained || bmp == null || board == null) {
            return null;
        }

        SquareSampler.Sample[] samples = sampleAll(bmp, board);

        if (samples == null) return null;

        char[][] grid = new char[8][8];

        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {

                int index = r * 8 + f;

                if (previousFrame != null
                        && previousLabels != null) {

                    float difference =
                            SquareSampler.lumDistance(
                                    samples[index].feature,
                                    previousFrame[index].feature
                            );

                    if (difference < frameChangeThreshold) {
                        grid[r][f] = previousLabels[index];
                        continue;
                    }
                }

                grid[r][f] = classifyOne(samples[index], r, f);
            }
        }

        return grid;
    }

    public synchronized void commitConfirmed(
            char[][] grid,
            Bitmap bmp,
            BoardDetector.Board board) {

        if (!trained || grid == null
                || bmp == null || board == null) {
            return;
        }

        SquareSampler.Sample[] samples = sampleAll(bmp, board);

        if (samples == null) return;

        previousFrame = samples;

        if (previousLabels == null) {
            previousLabels = new char[64];
        }

        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {

                char piece = grid[r][f];

                previousLabels[r * 8 + f] =
                        piece == 0 ? '.' : piece;
            }
        }
    }

    private SquareSampler.Sample[] sampleAll(
            Bitmap bmp,
            BoardDetector.Board board) {

        if (bmp == null || board == null
                || board.square < 8) {
            return null;
        }

        int rad = Math.max(
                4,
                (int) (board.square * 0.32f)
        );

        SquareSampler.Sample[] samples =
                new SquareSampler.Sample[64];

        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {

                int cx = Math.round(
                        board.left + (f + 0.5f) * board.square
                );

                int cy = Math.round(
                        board.top + (r + 0.5f) * board.square
                );

                samples[r * 8 + f] =
                        SquareSampler.sample(bmp, cx, cy, rad);
            }
        }

        return samples;
    }

    private float median(float[] values) {

        int middle = values.length / 2;

        if (values.length % 2 == 0) {
            return (values[middle - 1] + values[middle]) / 2f;
        }

        return values[middle];
    }

    private char[] flipLabels(char[] source) {

        char[] output = new char[64];

        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {

                output[r * 8 + f] =
                        source[(7 - r) * 8 + (7 - f)];
            }
        }

        return output;
    }

    private char classifyOne(
            SquareSampler.Sample sample,
            int r,
            int f) {

        if (sample.edgeEnergy < pieceColorThreshold) {
            return '.';
        }

        float whiteDistance =
                Math.abs(sample.meanLum - whitePieceLumAvg);

        float blackDistance =
                Math.abs(sample.meanLum - blackPieceLumAvg);

        boolean whitePiece =
                whiteDistance < blackDistance;

        boolean lightSquare =
                ((r + f) & 1) == 0;

        float bestDistance = Float.MAX_VALUE;

        char bestPiece = whitePiece ? 'P' : 'p';

        for (int i = 0; i < templates.length; i++) {

            char templatePiece = labels[i];

            if (templatePiece == '.') continue;

            if (Character.isUpperCase(templatePiece)
                    != whitePiece) {
                continue;
            }

            boolean templateLight =
                    (((i / 8) + (i % 8)) & 1) == 0;

            if (templateLight != lightSquare) continue;

            float distance = SquareSampler.lumDistance(
                    sample.feature,
                    templates[i].feature
            );

            if (distance < bestDistance) {
                bestDistance = distance;
                bestPiece = templatePiece;
            }
        }

        return bestPiece;
    }

    public synchronized void reset() {

        templates = null;
        labels = null;

        previousFrame = null;
        previousLabels = null;

        pieceColorThreshold = 0;
        frameChangeThreshold = 0;

        whitePieceLumAvg = 0;
        blackPieceLumAvg = 0;

        trained = false;
        bootstrapSide = null;

        Log.i(TAG, "Classifier reset");
    }
}
