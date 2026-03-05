package com.example.qupath.orthanc;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Client pour communiquer avec un serveur Orthanc
 */
public class OrthancClient {
    
    private final String baseUrl;
    private final OkHttpClient httpClient;
    private final Gson gson;
    private final String credentials;
    private final String username;
    private final String password;

    /**
     * Constructeur pour un serveur Orthanc sans authentification
     */
    public OrthancClient(String baseUrl) {
        this(baseUrl, null, null);
    }

    /**
     * Constructeur pour un serveur Orthanc avec authentification
     */
    public OrthancClient(String baseUrl, String username, String password) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        this.gson = new Gson();
        this.username = username;
        this.password = password;

        if (username != null && password != null) {
            String auth = username + ":" + password;
            this.credentials = "Basic " + Base64.getEncoder().encodeToString(auth.getBytes());
        } else {
            this.credentials = null;
        }
    }
    
    /**
     * Teste la connexion au serveur Orthanc
     */
    public boolean testConnection() {
        try {
            Request.Builder requestBuilder = new Request.Builder()
                .url(baseUrl + "/system")
                .get();
            
            if (credentials != null) {
                requestBuilder.header("Authorization", credentials);
            }
            
            Request request = requestBuilder.build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Récupère la liste de toutes les études
     */
    public List<OrthancStudy> getStudies() throws IOException {
        Request.Builder requestBuilder = new Request.Builder()
            .url(baseUrl + "/studies")
            .get();
        
        if (credentials != null) {
            requestBuilder.header("Authorization", credentials);
        }
        
        Request request = requestBuilder.build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Erreur lors de la récupération des études: " + response.code());
            }
            
            String body = response.body().string();
            JsonArray studyIds = gson.fromJson(body, JsonArray.class);
            
            List<OrthancStudy> studies = new ArrayList<>();
            for (int i = 0; i < studyIds.size(); i++) {
                String studyId = studyIds.get(i).getAsString();
                OrthancStudy study = getStudyDetails(studyId);
                if (study != null) {
                    studies.add(study);
                }
            }
            
            return studies;
        }
    }
    
    /**
     * Récupère les détails d'une étude
     */
    private OrthancStudy getStudyDetails(String studyId) {
        try {
            Request.Builder requestBuilder = new Request.Builder()
                .url(baseUrl + "/studies/" + studyId)
                .get();
            
            if (credentials != null) {
                requestBuilder.header("Authorization", credentials);
            }
            
            Request request = requestBuilder.build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return null;
                }
                
                String body = response.body().string();
                JsonObject json = gson.fromJson(body, JsonObject.class);
                
                OrthancStudy study = new OrthancStudy();
                study.setId(studyId);
                
                // Extraire les informations principales
                if (json.has("MainDicomTags")) {
                    JsonObject tags = json.getAsJsonObject("MainDicomTags");
                    study.setPatientName(tags.has("PatientName") ? tags.get("PatientName").getAsString() : "Unknown");
                    study.setStudyDescription(tags.has("StudyDescription") ? tags.get("StudyDescription").getAsString() : "");
                    study.setStudyDate(tags.has("StudyDate") ? tags.get("StudyDate").getAsString() : "");
                }
                
                // Récupérer les séries
                if (json.has("Series")) {
                    JsonArray seriesArray = json.getAsJsonArray("Series");
                    List<String> seriesIds = new ArrayList<>();
                    for (int i = 0; i < seriesArray.size(); i++) {
                        seriesIds.add(seriesArray.get(i).getAsString());
                    }
                    study.setSeriesIds(seriesIds);
                }
                
                return study;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * Récupère les détails d'une série
     */
    public OrthancSeries getSeriesDetails(String seriesId) throws IOException {
        Request.Builder requestBuilder = new Request.Builder()
            .url(baseUrl + "/series/" + seriesId)
            .get();
        
        if (credentials != null) {
            requestBuilder.header("Authorization", credentials);
        }
        
        Request request = requestBuilder.build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Erreur lors de la récupération de la série: " + response.code());
            }
            
            String body = response.body().string();
            JsonObject json = gson.fromJson(body, JsonObject.class);
            
            OrthancSeries series = new OrthancSeries();
            series.setId(seriesId);
            
            if (json.has("MainDicomTags")) {
                JsonObject tags = json.getAsJsonObject("MainDicomTags");
                series.setSeriesDescription(tags.has("SeriesDescription") ? tags.get("SeriesDescription").getAsString() : "");
                series.setModality(tags.has("Modality") ? tags.get("Modality").getAsString() : "");
            }
            
            if (json.has("Instances")) {
                JsonArray instancesArray = json.getAsJsonArray("Instances");
                List<String> instanceIds = new ArrayList<>();
                for (int i = 0; i < instancesArray.size(); i++) {
                    instanceIds.add(instancesArray.get(i).getAsString());
                }
                series.setInstanceIds(instanceIds);
            }
            
            return series;
        }
    }
    
    /**
     * Télécharge un fichier DICOM depuis Orthanc
     */
    public InputStream downloadInstance(String instanceId) throws IOException {
        Request.Builder requestBuilder = new Request.Builder()
            .url(baseUrl + "/instances/" + instanceId + "/file")
            .get();
        
        if (credentials != null) {
            requestBuilder.header("Authorization", credentials);
        }
        
        Request request = requestBuilder.build();
        
        Response response = httpClient.newCall(request).execute();
        
        if (!response.isSuccessful()) {
            response.close();
            throw new IOException("Erreur lors du téléchargement de l'instance: " + response.code());
        }
        
        return response.body().byteStream();
    }

    public String getBaseUrl() { return baseUrl; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }

    /**
     * Récupère les informations de la pyramide WSI d'une série via le plugin WSI d'Orthanc.
     * Endpoint : GET /wsi/pyramids/{seriesId}
     */
    public WsiPyramidInfo getWsiPyramidInfo(String seriesId) throws IOException {
        Request.Builder requestBuilder = new Request.Builder()
                .url(baseUrl + "/wsi/pyramids/" + seriesId)
                .get();
        if (credentials != null) requestBuilder.header("Authorization", credentials);

        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful())
                throw new IOException("Impossible de récupérer la pyramide WSI (série " + seriesId + "): " + response.code());

            String body = response.body().string();
            try {
                JsonObject json = gson.fromJson(body, JsonObject.class);
                // "Resolutions" = tableau de facteurs de zoom, un par niveau (ex: [1, 2, 4, 8])
                JsonArray resolutions = json.getAsJsonArray("Resolutions");
                int nLevels = resolutions.size();
                // Taille des tuiles : format "TilesSizes" (array par niveau) ou "TileWidth"/"TileHeight" (entiers)
                int tw, th;
                if (json.has("TilesSizes")) {
                    JsonArray firstTileSize = json.getAsJsonArray("TilesSizes").get(0).getAsJsonArray();
                    tw = firstTileSize.get(0).getAsInt();
                    th = firstTileSize.get(1).getAsInt();
                } else {
                    tw = json.get("TileWidth").getAsInt();
                    th = json.get("TileHeight").getAsInt();
                }
                // "Sizes" = tableau de [largeur, hauteur] de l'image par niveau
                JsonArray sizes = json.getAsJsonArray("Sizes");
                int[] w = new int[nLevels];
                int[] h = new int[nLevels];
                for (int i = 0; i < nLevels; i++) {
                    JsonArray sizeAtLevel = sizes.get(i).getAsJsonArray();
                    w[i] = sizeAtLevel.get(0).getAsInt();
                    h[i] = sizeAtLevel.get(1).getAsInt();
                }
                return new WsiPyramidInfo(nLevels, tw, th, w, h);
            } catch (Exception e) {
                throw new IOException("Structure JSON inattendue pour /wsi/pyramids/" + seriesId
                        + "\nJSON recu : " + body);
            }
        }
    }

    /**
     * Télécharge une tuile de la pyramide WSI.
     * Endpoint : GET /wsi/pyramids/{seriesId}/tiles/{level}/{col}/{row}
     */
    public byte[] getWsiTile(String seriesId, int level, int col, int row) throws IOException {
        // Endpoint officiel Orthanc WSI : /wsi/tiles/{seriesId}/{z}/{x}/{y}
        String url = baseUrl + "/wsi/tiles/" + seriesId + "/" + level + "/" + col + "/" + row;
        Request.Builder requestBuilder = new Request.Builder().url(url).get();
        if (credentials != null) requestBuilder.header("Authorization", credentials);

        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful())
                throw new IOException("Tuile introuvable (" + level + "," + col + "," + row + "): " + response.code());
            return response.body().bytes();
        }
    }

    /**
     * Télécharge une image PNG pré-rendue depuis Orthanc (décode le DICOM côté serveur)
     */
    public InputStream downloadInstanceRendered(String instanceId) throws IOException {
        Request.Builder requestBuilder = new Request.Builder()
            .url(baseUrl + "/instances/" + instanceId + "/rendered")
            .get();

        if (credentials != null) {
            requestBuilder.header("Authorization", credentials);
        }

        Request request = requestBuilder.build();
        Response response = httpClient.newCall(request).execute();

        if (!response.isSuccessful()) {
            response.close();
            throw new IOException("Erreur lors du rendu de l'instance: " + response.code());
        }

        return response.body().byteStream();
    }

    /**
     * Informations sur la pyramide WSI d'une série (réponse de /wsi/pyramids/{seriesId})
     */
    public static class WsiPyramidInfo {
        public final int levels;
        public final int tileWidth;
        public final int tileHeight;
        public final int[] totalWidth;
        public final int[] totalHeight;

        public WsiPyramidInfo(int levels, int tileWidth, int tileHeight, int[] totalWidth, int[] totalHeight) {
            this.levels = levels;
            this.tileWidth = tileWidth;
            this.tileHeight = tileHeight;
            this.totalWidth = totalWidth;
            this.totalHeight = totalHeight;
        }
    }

    /**
     * Classe représentant une étude Orthanc
     */
    public static class OrthancStudy {
        private String id;
        private String patientName;
        private String studyDescription;
        private String studyDate;
        private List<String> seriesIds;
        
        // Getters et setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        
        public String getPatientName() { return patientName; }
        public void setPatientName(String patientName) { this.patientName = patientName; }
        
        public String getStudyDescription() { return studyDescription; }
        public void setStudyDescription(String studyDescription) { this.studyDescription = studyDescription; }
        
        public String getStudyDate() { return studyDate; }
        public void setStudyDate(String studyDate) { this.studyDate = studyDate; }
        
        public List<String> getSeriesIds() { return seriesIds; }
        public void setSeriesIds(List<String> seriesIds) { this.seriesIds = seriesIds; }
        
        @Override
        public String toString() {
            return String.format("%s - %s (%s)", patientName, studyDescription, studyDate);
        }
    }
    
    /**
     * Classe représentant une série Orthanc
     */
    public static class OrthancSeries {
        private String id;
        private String seriesDescription;
        private String modality;
        private List<String> instanceIds;
        
        // Getters et setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        
        public String getSeriesDescription() { return seriesDescription; }
        public void setSeriesDescription(String seriesDescription) { this.seriesDescription = seriesDescription; }
        
        public String getModality() { return modality; }
        public void setModality(String modality) { this.modality = modality; }
        
        public List<String> getInstanceIds() { return instanceIds; }
        public void setInstanceIds(List<String> instanceIds) { this.instanceIds = instanceIds; }
        
        @Override
        public String toString() {
            return String.format("%s (%s) - %d images", seriesDescription, modality, instanceIds != null ? instanceIds.size() : 0);
        }
    }
}
