package dev.mmrlx.thread;

interface IRootThread {
    // callableFd: root reads serialized RootCallable from this pipe (main → root)
    // resultFd:   root writes serialized result to this pipe  (root → main)
    void execute(in ParcelFileDescriptor callableFd, in ParcelFileDescriptor resultFd);
}