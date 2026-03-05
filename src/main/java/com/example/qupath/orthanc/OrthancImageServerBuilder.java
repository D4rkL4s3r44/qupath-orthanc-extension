package com.example.qupath.orthanc;

import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/**
 * Builder SPI pour OrthancImageServer.
 * Enregistré via META-INF/services pour que QuPath puisse reconstruire
 * le serveur depuis l'URI stockée dans le projet.
 */
public class OrthancImageServerBuilder implements ImageServerBuilder<BufferedImage> {

    @Override
    public ImageServerBuilder.UriImageSupport<BufferedImage> checkImageSupport(URI uri, String... args) throws IOException {
        if (!"orthanc".equals(uri.getScheme())) {
            return null;
        }
        final String[] finalArgs = args != null ? args.clone() : new String[0];
        // Utilise un ServerBuilder direct pour éviter la récursion infinie :
        // DefaultImageServerBuilder.build() → checkImageSupport() → DefaultImageServerBuilder.build() → ...
        var directBuilder = new ImageServerBuilder.ServerBuilder<BufferedImage>() {
            @Override
            public ImageServer<BufferedImage> build() throws Exception {
                return OrthancImageServerBuilder.this.buildServer(uri, finalArgs);
            }
            @Override
            public Collection<URI> getURIs() {
                return Collections.singletonList(uri);
            }
            @Override
            public ImageServerBuilder.ServerBuilder<BufferedImage> updateURIs(Map<URI, URI> replacements) {
                URI replacement = replacements.getOrDefault(uri, uri);
                return ImageServerBuilder.DefaultImageServerBuilder.createInstance(
                        OrthancImageServerBuilder.class, replacement, finalArgs);
            }
        };
        return ImageServerBuilder.UriImageSupport.createInstance(OrthancImageServerBuilder.class, 1f, directBuilder);
    }

    @Override
    public ImageServer<BufferedImage> buildServer(URI uri, String... args) throws Exception {
        String host = uri.getHost();
        int port = uri.getPort();
        if (port == -1) port = 80;
        // Le path est "/series/{seriesId}"
        String seriesId = uri.getPath().replaceFirst("^/series/", "");
        String baseUrl = "http://" + host + ":" + port;

        OrthancClient client;
        String userInfo = uri.getUserInfo();
        if (userInfo != null && userInfo.contains(":")) {
            String[] parts = userInfo.split(":", 2);
            client = new OrthancClient(baseUrl, parts[0], parts[1]);
        } else {
            client = new OrthancClient(baseUrl);
        }

        return new OrthancImageServer(client, seriesId);
    }

    @Override
    public String getName() {
        return "Orthanc WSI Builder";
    }

    @Override
    public String getDescription() {
        return "Serveur d'image pour les pyramides WSI hébergées sur Orthanc";
    }

    @Override
    public Class<BufferedImage> getImageType() {
        return BufferedImage.class;
    }
}
