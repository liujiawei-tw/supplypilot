package com.supplypilot.document.service;

import org.springframework.web.multipart.MultipartFile;

public interface DocumentStorageService {

    String store(MultipartFile file, String extension);

    void delete(String storagePath);
}
