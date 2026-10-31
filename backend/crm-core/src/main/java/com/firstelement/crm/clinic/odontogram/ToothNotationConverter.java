package com.firstelement.crm.clinic.odontogram;

import java.util.HashMap;
import java.util.Map;

/**
 * Canonical FDI / ISO 3950 dental notation conversion and Clinical Attachment Loss (CAL) calculator.
 *
 * <p>Storage is ALWAYS canonical FDI (11..48 adult, 51..85 primary). Display conversion to Universal
 * (1..32, A..T) and Palmer is performed at the boundary.
 */
public final class ToothNotationConverter {

    private static final Map<Integer, String> FDI_TO_UNIVERSAL = new HashMap<>();
    private static final Map<String, Integer> UNIVERSAL_TO_FDI = new HashMap<>();

    static {
        // Adult Teeth: 11..48
        int[] q1Fdi = {18, 17, 16, 15, 14, 13, 12, 11};
        for (int i = 0; i < q1Fdi.length; i++) {
            String univ = String.valueOf(i + 1);
            FDI_TO_UNIVERSAL.put(q1Fdi[i], univ);
            UNIVERSAL_TO_FDI.put(univ, q1Fdi[i]);
        }

        int[] q2Fdi = {21, 22, 23, 24, 25, 26, 27, 28};
        for (int i = 0; i < q2Fdi.length; i++) {
            String univ = String.valueOf(i + 9);
            FDI_TO_UNIVERSAL.put(q2Fdi[i], univ);
            UNIVERSAL_TO_FDI.put(univ, q2Fdi[i]);
        }

        int[] q3Fdi = {38, 37, 36, 35, 34, 33, 32, 31};
        for (int i = 0; i < q3Fdi.length; i++) {
            String univ = String.valueOf(i + 17);
            FDI_TO_UNIVERSAL.put(q3Fdi[i], univ);
            UNIVERSAL_TO_FDI.put(univ, q3Fdi[i]);
        }

        int[] q4Fdi = {41, 42, 43, 44, 45, 46, 47, 48};
        for (int i = 0; i < q4Fdi.length; i++) {
            String univ = String.valueOf(i + 25);
            FDI_TO_UNIVERSAL.put(q4Fdi[i], univ);
            UNIVERSAL_TO_FDI.put(univ, q4Fdi[i]);
        }

        // Primary / Deciduous Teeth: 51..85
        int[] q5Fdi = {55, 54, 53, 52, 51};
        char[] q5Univ = {'A', 'B', 'C', 'D', 'E'};
        for (int i = 0; i < q5Fdi.length; i++) {
            String univ = String.valueOf(q5Univ[i]);
            FDI_TO_UNIVERSAL.put(q5Fdi[i], univ);
            UNIVERSAL_TO_FDI.put(univ, q5Fdi[i]);
        }

        int[] q6Fdi = {61, 62, 63, 64, 65};
        char[] q6Univ = {'F', 'G', 'H', 'I', 'J'};
        for (int i = 0; i < q6Fdi.length; i++) {
            String univ = String.valueOf(q6Univ[i]);
            FDI_TO_UNIVERSAL.put(q6Fdi[i], univ);
            UNIVERSAL_TO_FDI.put(univ, q6Fdi[i]);
        }

        int[] q7Fdi = {75, 74, 73, 72, 71};
        char[] q7Univ = {'K', 'L', 'M', 'N', 'O'};
        for (int i = 0; i < q7Fdi.length; i++) {
            String univ = String.valueOf(q7Univ[i]);
            FDI_TO_UNIVERSAL.put(q7Fdi[i], univ);
            UNIVERSAL_TO_FDI.put(univ, q7Fdi[i]);
        }

        int[] q8Fdi = {81, 82, 83, 84, 85};
        char[] q8Univ = {'P', 'Q', 'R', 'S', 'T'};
        for (int i = 0; i < q8Fdi.length; i++) {
            String univ = String.valueOf(q8Univ[i]);
            FDI_TO_UNIVERSAL.put(q8Fdi[i], univ);
            UNIVERSAL_TO_FDI.put(univ, q8Fdi[i]);
        }
    }

    private ToothNotationConverter() {
    }

    public record NotationDisplay(int fdi, String universal, String palmerQuadrant, String palmerIndex) {
    }

    /** Validates whether the number represents a valid ISO 3950 / FDI tooth number. */
    public static boolean isValidFdi(int fdi) {
        int quadrant = fdi / 10;
        int position = fdi % 10;
        if (quadrant >= 1 && quadrant <= 4) {
            return position >= 1 && position <= 8;
        } else if (quadrant >= 5 && quadrant <= 8) {
            return position >= 1 && position <= 5;
        }
        return false;
    }

    /** Asserts that the number is a valid ISO 3950 / FDI tooth number or throws IllegalArgumentException. */
    public static void validateFdi(int fdi) {
        if (!isValidFdi(fdi)) {
            throw new IllegalArgumentException("Invalid or non-canonical FDI tooth number: " + fdi);
        }
    }

    /** Returns comprehensive notation display representations for UI rendering. */
    public static NotationDisplay toDisplay(int fdi) {
        validateFdi(fdi);
        String universal = fdiToUniversal(fdi);
        int quadrant = fdi / 10;
        int position = fdi % 10;
        String quadStr = switch (quadrant) {
            case 1, 5 -> "UR";
            case 2, 6 -> "UL";
            case 3, 7 -> "LL";
            case 4, 8 -> "LR";
            default -> "??";
        };
        String palmerIndex = quadrant >= 5
                ? String.valueOf((char) ('A' + position - 1))
                : String.valueOf(position);
        return new NotationDisplay(fdi, universal, quadStr, palmerIndex);
    }

    /** Converts canonical FDI tooth number to Universal numbering system (1..32 or A..T). */
    public static String fdiToUniversal(int fdi) {
        String univ = FDI_TO_UNIVERSAL.get(fdi);
        if (univ == null) {
            throw new IllegalArgumentException("Invalid or non-canonical FDI tooth number: " + fdi);
        }
        return univ;
    }

    /** Converts Universal tooth identifier (1..32, A..T) to canonical FDI. */
    public static int universalToFdi(String universal) {
        if (universal == null || universal.isBlank()) {
            throw new IllegalArgumentException("Universal tooth identifier cannot be blank");
        }
        Integer fdi = UNIVERSAL_TO_FDI.get(universal.trim().toUpperCase());
        if (fdi == null) {
            throw new IllegalArgumentException("Invalid Universal tooth identifier: " + universal);
        }
        return fdi;
    }

    /** Converts canonical FDI to Palmer notation format (e.g. UR1, UL8, LR5, LL3). */
    public static String fdiToPalmer(int fdi) {
        if (!isValidFdi(fdi)) {
            throw new IllegalArgumentException("Invalid or non-canonical FDI tooth number: " + fdi);
        }
        int quadrant = fdi / 10;
        int position = fdi % 10;
        String quadStr = switch (quadrant) {
            case 1, 5 -> "UR";
            case 2, 6 -> "UL";
            case 3, 7 -> "LL";
            case 4, 8 -> "LR";
            default -> "??";
        };
        if (quadrant >= 5) {
            char primaryLetter = (char) ('A' + position - 1);
            return quadStr + primaryLetter;
        }
        return quadStr + position;
    }

    /**
     * Consistently derives Clinical Attachment Loss (CAL) from Probing Depth (PD) and Gingival Margin (GM).
     *
     * <p>CAL = PD + GM (in mm).
     * Positive GM indicates gingival recession (apical to cementoenamel junction).
     * Negative GM indicates gingival hyperplasia/overgrowth (coronal to CEJ).
     *
     * @param probingDepth probing depth in mm
     * @param gingivalMargin gingival margin position relative to CEJ in mm (0 if at CEJ)
     * @return derived CAL in mm, or null if probingDepth is null
     */
    public static Integer deriveCal(Integer probingDepth, Integer gingivalMargin) {
        if (probingDepth == null) {
            return null;
        }
        int gm = gingivalMargin == null ? 0 : gingivalMargin;
        return probingDepth + gm;
    }
}
