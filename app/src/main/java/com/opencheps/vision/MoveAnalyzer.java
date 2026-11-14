
package com.opencheps.vision;

import com.opencheps.util.Side;

import java.util.ArrayList;
import java.util.List;

public class MoveAnalyzer {

    public enum Kind {
        NONE, QUIET, CAPTURE, CASTLE,
        EN_PASSANT, PROMOTION, UNKNOWN
    }

    public static class Result {
        public final Kind kind;
        public final Side mover;
        public final int changeCount;

        public Result(Kind kind, Side mover, int changeCount) {
            this.kind = kind;
            this.mover = mover;
            this.changeCount = changeCount;
        }
    }

    private static class SquareChange {
        final int row;
        final int file;
        final char before;
        final char after;

        SquareChange(int row, int file, char before, char after) {
            this.row = row;
            this.file = file;
            this.before = before;
            this.after = after;
        }
    }

    public static Result analyze(char[][] prev, char[][] curr) {

        if (!validBoard(prev) || !validBoard(curr)) {
            return unknown(0);
        }

        List<SquareChange> emptied = new ArrayList<>();
        List<SquareChange> filled = new ArrayList<>();
        List<SquareChange> replaced = new ArrayList<>();

        int changes = 0;

        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {

                char p = normalize(prev[r][f]);
                char c = normalize(curr[r][f]);

                if (p == c) continue;

                changes++;

                SquareChange change =
                        new SquareChange(r, f, p, c);

                if (p == '.') {
                    filled.add(change);
                } else if (c == '.') {
                    emptied.add(change);
                } else {
                    replaced.add(change);
                }
            }
        }

        if (changes == 0) {
            return new Result(Kind.NONE, null, 0);
        }

        // Normal move or promotion
        if (emptied.size() == 1 &&
            filled.size() == 1 &&
            replaced.isEmpty()) {

            SquareChange from = emptied.get(0);
            SquareChange to = filled.get(0);

            if (!sameColor(from.before, to.after)) {
                return unknown(changes);
            }

            Side mover = getSide(from.before);

            if (isPromotion(from.before, to.after)) {
                return result(Kind.PROMOTION, mover, changes);
            }

            return result(Kind.QUIET, mover, changes);
        }

        // Capture or capture promotion
        if (emptied.size() == 1 &&
            filled.isEmpty() &&
            replaced.size() == 1) {

            SquareChange from = emptied.get(0);
            SquareChange to = replaced.get(0);

            if (sameColor(from.before, to.before)) {
                return unknown(changes);
            }

            if (!sameColor(from.before, to.after)) {
                return unknown(changes);
            }

            Side mover = getSide(from.before);

            if (isPromotion(from.before, to.after)) {
                return result(Kind.PROMOTION, mover, changes);
            }

            return result(Kind.CAPTURE, mover, changes);
        }

        // En passant
        if (emptied.size() == 2 &&
            filled.size() == 1 &&
            replaced.isEmpty()) {

            SquareChange a = emptied.get(0);
            SquareChange b = emptied.get(1);
            SquareChange to = filled.get(0);

            boolean pawns =
                    isPawn(a.before) &&
                    isPawn(b.before) &&
                    isPawn(to.after);

            boolean oppositeColors =
                    !sameColor(a.before, b.before);

            boolean destinationColor =
                    sameColor(a.before, to.after);

            SquareChange from = a.file != to.file ? a : b;
SquareChange victim = a.file == to.file ? a : b;

boolean validRanks =
        victim.file == to.file &&
        victim.row == from.row &&
        Math.abs(from.file - to.file) == 1 &&
        Math.abs(from.row - to.row) == 1;

            if (pawns && oppositeColors &&
                destinationColor && validRanks) {

                return result(
                        Kind.EN_PASSANT,
                        getSide(to.before),
                        changes
                );
            }

            return unknown(changes);
        }

        // Castling
        if (emptied.size() == 2 &&
            filled.size() == 2 &&
            replaced.isEmpty()) {

            char a = emptied.get(0).before;
            char b = emptied.get(1).before;
            char c = filled.get(0).after;
            char d = filled.get(1).after;

            boolean sameColor =
                    sameColor(a, b) &&
                    sameColor(a, c) &&
                    sameColor(a, d);

            boolean kingAndRook =
                    containsKingAndRook(a, b) &&
                    containsKingAndRook(c, d);

            if (sameColor && kingAndRook) {
                return result(
                        Kind.CASTLE,
                        getSide(a),
                        changes
                );
            }
        }

        return unknown(changes);
    }

    private static boolean validBoard(char[][] board) {
        if (board == null || board.length != 8) {
            return false;
        }

        for (char[] row : board) {
            if (row == null || row.length != 8) {
                return false;
            }
        }

        return true;
    }

    private static char normalize(char piece) {
        return piece == 0 ? '.' : piece;
    }

    private static boolean sameColor(char a, char b) {
        return Character.isUpperCase(a) ==
               Character.isUpperCase(b);
    }

    private static Side getSide(char piece) {
        return Character.isUpperCase(piece)
                ? Side.WHITE
                : Side.BLACK;
    }

    private static boolean isPawn(char piece) {
        return Character.toLowerCase(piece) == 'p';
    }

    private static boolean isPromotion(char from, char to) {
        return isPawn(from) &&
               Character.toLowerCase(to) != 'p';
    }

    private static boolean containsKingAndRook(
            char a, char b) {

        return
            (Character.toLowerCase(a) == 'k' &&
             Character.toLowerCase(b) == 'r') ||
            (Character.toLowerCase(a) == 'r' &&
             Character.toLowerCase(b) == 'k');
    }

    private static Result result(
            Kind kind, Side mover, int changes) {

        return new Result(kind, mover, changes);
    }

    private static Result unknown(int changes) {
        return new Result(Kind.UNKNOWN, null, changes);
    }
}
