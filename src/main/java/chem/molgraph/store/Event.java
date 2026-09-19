package chem.molgraph.store;

public record Event(long seq, String type, String payloadJson, String payloadHash, String prevHash, long timestamp) {
    public String chainHash() {
        return chem.molgraph.engine.Canonical.sha256(prevHash + "|" + seq + "|" + type + "|" + payloadHash);
    }
}
