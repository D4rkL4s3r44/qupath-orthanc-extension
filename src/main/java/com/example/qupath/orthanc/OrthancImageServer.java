package com.example.qupath.orthanc;

import qupath.lib.images.servers.AbstractTileableImageServer;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;
import qupath.lib.regions.RegionRequest;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;

/**
 * Serveur d'image QuPath qui récupère les tuiles depuis le plugin WSI d'Orthanc.
 * Utilise les endpoints /wsi/pyramids/{seriesId}/tiles/{level}/{col}/{row}.
 */
public class OrthancImageServer extends AbstractTileableImageServer {

    private final OrthancClient client;
    private final String seriesId;
    private final OrthancClient.WsiPyramidInfo pyramidInfo;
    private final ImageServerMetadata originalMetadata;

    public OrthancImageServer(OrthancClient client, String seriesId) throws IOException {
        super();
        this.client = client;
        this.seriesId = seriesId;
        this.pyramidInfo = client.getWsiPyramidInfo(seriesId);

        // Calcul des downsamples : niveau 0 = résolution max (downsample 1.0)
        double[] downsamples = new double[pyramidInfo.levels];
        for (int i = 0; i < pyramidInfo.levels; i++) {
            downsamples[i] = (double) pyramidInfo.totalWidth[0] / pyramidInfo.totalWidth[i];
        }

        this.originalMetadata = new ImageServerMetadata.Builder()
                .name(seriesId)
                .width(pyramidInfo.totalWidth[0])
                .height(pyramidInfo.totalHeight[0])
                .preferredTileSize(pyramidInfo.tileWidth, pyramidInfo.tileHeight)
                .levelsFromDownsamples(downsamples)
                .channels(ImageChannel.getDefaultRGBChannels())
                .rgb(true)
                .pixelType(PixelType.UINT8)
                .build();

        setMetadata(originalMetadata);
    }

    @Override
    protected BufferedImage readTile(TileRequest tileRequest) throws IOException {
        int level = tileRequest.getLevel();
        double downsample = getDownsampleForResolution(level);
        RegionRequest region = tileRequest.getRegionRequest();

        // Convertir les coordonnées pleine résolution en indices col/row au niveau courant
        int col = (int) (region.getX() / downsample / pyramidInfo.tileWidth);
        int row = (int) (region.getY() / downsample / pyramidInfo.tileHeight);

        try {
            byte[] data = client.getWsiTile(seriesId, level, col, row);
            if (data == null || data.length == 0) {
                return createBlankTile(tileRequest, downsample);
            }
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            return img != null ? img : createBlankTile(tileRequest, downsample);
        } catch (IOException e) {
            // Tuile absente (hors limites ou manquante) : retourner du blanc
            return createBlankTile(tileRequest, downsample);
        }
    }

    private BufferedImage createBlankTile(TileRequest tileRequest, double downsample) {
        int w = (int) Math.max(1, Math.ceil(tileRequest.getRegionRequest().getWidth() / downsample));
        int h = (int) Math.max(1, Math.ceil(tileRequest.getRegionRequest().getHeight() / downsample));
        BufferedImage blank = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = blank.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return blank;
    }

    /** Méthode publique pour obtenir le builder (utilisée lors de l'ajout au projet). */
    public ImageServerBuilder.ServerBuilder<BufferedImage> getServerBuilder() {
        return createServerBuilder();
    }

    @Override
    protected ImageServerBuilder.ServerBuilder<BufferedImage> createServerBuilder() {
        return ImageServerBuilder.DefaultImageServerBuilder.createInstance(OrthancImageServerBuilder.class, buildURI());
    }

    @Override
    protected String createID() {
        return buildURI().toString();
    }

    @Override
    public String getServerType() {
        return "Orthanc WSI";
    }

    @Override
    public ImageServerMetadata getOriginalMetadata() {
        return originalMetadata;
    }

    @Override
    public Collection<URI> getURIs() {
        return Collections.singletonList(buildURI());
    }

    private URI buildURI() {
        try {
            java.net.URL url = new java.net.URL(client.getBaseUrl());
            String host = url.getHost();
            int port = url.getPort() == -1 ? url.getDefaultPort() : url.getPort();

            String encodedBase = URLEncoder.encode(client.getBaseUrl(), StandardCharsets.UTF_8);
            String query = "base=" + encodedBase;

            if (client.getUsername() != null && !client.getUsername().isEmpty()) {
                query += "&user=" + URLEncoder.encode(client.getUsername(), StandardCharsets.UTF_8)
                       + "&pass=" + URLEncoder.encode(client.getPassword(), StandardCharsets.UTF_8);
            }

            return new URI("orthanc", null, host, port, "/series/" + seriesId, query, null);
        } catch (Exception e) {
            return URI.create("orthanc://unknown/series/" + seriesId);
        }
    }
}
