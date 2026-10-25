package com.supplypilot.document.service;

import java.io.InputStream;

import org.springframework.web.multipart.MultipartFile;

public interface DocumentStorageService {

    String store(MultipartFile file, String extension);

    /** Opens a server-owned stored document. Callers must close the returned stream. */
    InputStream read(String storagePath);

    /** Returns the size of a server-owned stored document without exposing its path to API callers. */
    long size(String storagePath);

    void delete(String storagePath);
}
