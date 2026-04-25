package com.webrekas.addon.data;

/**
 * The 14 (edition, version) combinations orefinder.gg's site exposes, mirroring
 * the platform dropdown one-for-one. Encoded values match the WASM's internal
 * {@code E} and {@code m} enums (see plan file).
 */
public enum OrePlatform {
    BEDROCK_1_17    (2, 101700, "Bedrock 1.17"),
    BEDROCK_1_18    (2, 101800, "Bedrock 1.18"),
    BEDROCK_1_19    (2, 101900, "Bedrock 1.19"),
    BEDROCK_1_20    (2, 102000, "Bedrock 1.20"),
    BEDROCK_1_20_30 (2, 102003, "Bedrock 1.20.30"),
    BEDROCK_1_21    (2, 102100, "Bedrock 1.21"),
    BEDROCK_26      (2, 102100, "Bedrock 26"),
    JAVA_1_17       (1, 101700, "Java 1.17"),
    JAVA_1_18       (1, 101800, "Java 1.18"),
    JAVA_1_19       (1, 101900, "Java 1.19"),
    JAVA_1_20       (1, 102000, "Java 1.20"),
    JAVA_1_20_2     (1, 102002, "Java 1.20.2"),
    JAVA_1_21       (1, 102100, "Java 1.21"),
    JAVA_26         (1, 102100, "Java 26");

    private final int edition;
    private final int version;
    private final String label;

    OrePlatform(int edition, int version, String label) {
        this.edition = edition;
        this.version = version;
        this.label = label;
    }

    public int edition() { return edition; }
    public int version() { return version; }
    public String label() { return label; }

    @Override
    public String toString() { return label; }
}
