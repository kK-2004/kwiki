package com.kwiki.eval;

/** 三个嵌套测试集：tier ≤ size 的题都属于该集合。 */
public enum EvalDatasetSize {
    Q50(50), Q100(100), Q200(200);

    private final int size;

    EvalDatasetSize(int size) {
        this.size = size;
    }

    public int size() {
        return size;
    }

    public String label() {
        return size + "q";
    }

    /** Langfuse 中的数据集名。 */
    public String datasetName() {
        return "kwiki-rag-" + label();
    }

    public static EvalDatasetSize parse(String label) {
        for (EvalDatasetSize value : values()) {
            if (value.label().equalsIgnoreCase(label)) return value;
        }
        throw new IllegalArgumentException("unknown eval dataset: " + label);
    }
}
