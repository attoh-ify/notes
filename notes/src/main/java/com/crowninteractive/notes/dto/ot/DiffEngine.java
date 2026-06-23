package com.crowninteractive.notes.dto.ot;

import java.util.*;

public class DiffEngine {

    public enum OpType {
        INSERT, DELETE, EQUAL
    }

    public static class Diff {
        public OpType op;
        public String text;

        public Diff(OpType op, String text) {
            this.op = op;
            this.text = text;
        }
    }

    public static List<Diff> diff(String text1, String text2) {
        if (Objects.equals(text1, text2)) {
            if (!text1.isEmpty()) {
                return Collections.singletonList(new Diff(OpType.EQUAL, text1));
            }
            return Collections.emptyList();
        }

        int commonPrefix = commonPrefix(text1, text2);
        String prefix = text1.substring(0, commonPrefix);

        text1 = text1.substring(commonPrefix);
        text2 = text2.substring(commonPrefix);

        int commonSuffix = commonSuffix(text1, text2);
        String suffix = text1.substring(text1.length() - commonSuffix);

        text1 = text1.substring(0, text1.length() - commonSuffix);
        text2 = text2.substring(0, text2.length() - commonSuffix);

        List<Diff> diffs = compute(text1, text2);

        if (!prefix.isEmpty()) {
            diffs.add(0, new Diff(OpType.EQUAL, prefix));
        }
        if (!suffix.isEmpty()) {
            diffs.add(new Diff(OpType.EQUAL, suffix));
        }

        return cleanup(diffs);
    }

    private static List<Diff> compute(String text1, String text2) {
        if (text1.isEmpty()) {
            return Collections.singletonList(new Diff(OpType.INSERT, text2));
        }
        if (text2.isEmpty()) {
            return Collections.singletonList(new Diff(OpType.DELETE, text1));
        }

        int i = text1.indexOf(text2);
        if (i != -1) {
            return Arrays.asList(
                    new Diff(OpType.DELETE, text1.substring(0, i)),
                    new Diff(OpType.EQUAL, text2),
                    new Diff(OpType.DELETE, text1.substring(i + text2.length()))
            );
        }

        if (text1.length() == 1 || text2.length() == 1) {
            return Arrays.asList(
                    new Diff(OpType.DELETE, text1),
                    new Diff(OpType.INSERT, text2)
            );
        }

        return bisect(text1, text2);
    }

    private static List<Diff> bisect(String text1, String text2) {
        int n = text1.length();
        int m = text2.length();
        int max = (n + m + 1) / 2;
        int vOffset = max;
        int vLength = 2 * max;
        int[] v1 = new int[vLength];
        int[] v2 = new int[vLength];

        Arrays.fill(v1, -1);
        Arrays.fill(v2, -1);

        v1[vOffset + 1] = 0;
        v2[vOffset + 1] = 0;

        int delta = n - m;
        boolean front = (delta % 2 != 0);

        for (int d = 0; d < max; d++) {
            for (int k = -d; k <= d; k += 2) {
                int kOffset = vOffset + k;

                int x;
                if (k == -d || (k != d && v1[kOffset - 1] < v1[kOffset + 1])) {
                    x = v1[kOffset + 1];
                } else {
                    x = v1[kOffset - 1] + 1;
                }

                int y = x - k;

                while (x < n && y < m && text1.charAt(x) == text2.charAt(y)) {
                    x++;
                    y++;
                }

                v1[kOffset] = x;

                if (front) {
                    int k2Offset = vOffset + delta - k;
                    if (k2Offset >= 0 && k2Offset < vLength && v2[k2Offset] != -1) {
                        int x2 = n - v2[k2Offset];
                        if (x >= x2) {
                            return split(text1, text2, x, y);
                        }
                    }
                }
            }

            for (int k = -d; k <= d; k += 2) {
                int kOffset = vOffset + k;

                int x;
                if (k == -d || (k != d && v2[kOffset - 1] < v2[kOffset + 1])) {
                    x = v2[kOffset + 1];
                } else {
                    x = v2[kOffset - 1] + 1;
                }

                int y = x - k;

                while (x < n && y < m &&
                        text1.charAt(n - x - 1) == text2.charAt(m - y - 1)) {
                    x++;
                    y++;
                }

                v2[kOffset] = x;

                if (!front) {
                    int k1Offset = vOffset + delta - k;
                    if (k1Offset >= 0 && k1Offset < vLength && v1[k1Offset] != -1) {
                        int x1 = v1[k1Offset];
                        int x2 = n - x;
                        if (x1 >= x2) {
                            return split(text1, text2, x1, x1 - k1Offset + vOffset);
                        }
                    }
                }
            }
        }

        return Arrays.asList(
                new Diff(OpType.DELETE, text1),
                new Diff(OpType.INSERT, text2)
        );
    }

    private static List<Diff> split(String text1, String text2, int x, int y) {
        List<Diff> a = diff(text1.substring(0, x), text2.substring(0, y));
        List<Diff> b = diff(text1.substring(x), text2.substring(y));
        List<Diff> result = new ArrayList<>(a);
        result.addAll(b);
        return result;
    }

    private static int commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            if (a.charAt(i) != b.charAt(i)) return i;
        }
        return n;
    }

    private static int commonSuffix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        for (int i = 1; i <= n; i++) {
            if (a.charAt(a.length() - i) != b.charAt(b.length() - i)) return i - 1;
        }
        return n;
    }

    private static List<Diff> cleanup(List<Diff> diffs) {
        List<Diff> result = new ArrayList<>();
        for (Diff d : diffs) {
            if (!d.text.isEmpty()) {
                if (!result.isEmpty() && result.get(result.size() - 1).op == d.op) {
                    result.get(result.size() - 1).text += d.text;
                } else {
                    result.add(new Diff(d.op, d.text));
                }
            }
        }
        return result;
    }
}