package com.example.qupath.orthanc;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Dialog amélioré pour importer des images DICOM depuis Orthanc
 * Supporte l'import d'instances uniques ou de séries complètes
 */
public class EnhancedOrthancImportDialog extends Dialog<EnhancedOrthancImportDialog.OrthancImportResult> {
    
    private TextField urlField;
    private TextField usernameField;
    private PasswordField passwordField;
    private CheckBox authCheckBox;
    private ComboBox<OrthancClient.OrthancStudy> studyComboBox;
    private ComboBox<OrthancClient.OrthancSeries> seriesComboBox;
    private ListView<String> instanceListView;
    private Button connectButton;
    private RadioButton importSingleRadio;
    private RadioButton importSeriesRadio;
    private Label statusLabel;
    
    private OrthancClient client;
    
    public EnhancedOrthancImportDialog() {
        setTitle("Importer depuis Orthanc");
        setHeaderText("Navigation et import d'images DICOM depuis Orthanc");
        
        // Boutons
        ButtonType importButtonType = new ButtonType("Importer", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(importButtonType, ButtonType.CANCEL);
        
        // Layout principal
        VBox mainLayout = new VBox(15);
        mainLayout.setPadding(new Insets(10));
        
        // === Section 1 : Connexion ===
        Label connectionTitle = new Label("1. Connexion au serveur Orthanc");
        connectionTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        
        GridPane connectionPane = new GridPane();
        connectionPane.setHgap(10);
        connectionPane.setVgap(10);
        
        Label urlLabel = new Label("URL Orthanc:");
        urlField = new TextField("http://localhost:8042");
        urlField.setPrefWidth(300);
        
        authCheckBox = new CheckBox("Authentification requise");
        
        Label usernameLabel = new Label("Utilisateur:");
        usernameField = new TextField();
        usernameField.setDisable(true);
        
        Label passwordLabel = new Label("Mot de passe:");
        passwordField = new PasswordField();
        passwordField.setDisable(true);
        
        authCheckBox.setOnAction(e -> {
            boolean selected = authCheckBox.isSelected();
            usernameField.setDisable(!selected);
            passwordField.setDisable(!selected);
        });
        
        connectButton = new Button("Se connecter");
        connectButton.setOnAction(e -> connectToOrthanc());
        
        connectionPane.add(urlLabel, 0, 0);
        connectionPane.add(urlField, 1, 0);
        connectionPane.add(authCheckBox, 1, 1);
        connectionPane.add(usernameLabel, 0, 2);
        connectionPane.add(usernameField, 1, 2);
        connectionPane.add(passwordLabel, 0, 3);
        connectionPane.add(passwordField, 1, 3);
        connectionPane.add(connectButton, 1, 4);
        
        // === Section 2 : Sélection ===
        Label selectionTitle = new Label("2. Sélection de l'image ou série");
        selectionTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        
        Label studyLabel = new Label("Étude:");
        studyComboBox = new ComboBox<>();
        studyComboBox.setMaxWidth(Double.MAX_VALUE);
        studyComboBox.setDisable(true);
        studyComboBox.setOnAction(e -> onStudySelected());
        
        Label seriesLabel = new Label("Série:");
        seriesComboBox = new ComboBox<>();
        seriesComboBox.setMaxWidth(Double.MAX_VALUE);
        seriesComboBox.setDisable(true);
        seriesComboBox.setOnAction(e -> onSeriesSelected());
        
        Label instanceLabel = new Label("Instances:");
        instanceListView = new ListView<>();
        instanceListView.setPrefHeight(100);
        instanceListView.setDisable(true);
        instanceListView.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        
        // === Section 3 : Options d'import ===
        Label importTitle = new Label("3. Options d'import");
        importTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        
        ToggleGroup importGroup = new ToggleGroup();
        
        importSingleRadio = new RadioButton("Importer une seule instance");
        importSingleRadio.setToggleGroup(importGroup);
        importSingleRadio.setSelected(true);
        
        importSeriesRadio = new RadioButton("Importer toute la série");
        importSeriesRadio.setToggleGroup(importGroup);
        
        VBox importOptions = new VBox(5);
        importOptions.getChildren().addAll(importSingleRadio, importSeriesRadio);
        
        // Label de statut
        statusLabel = new Label("");
        statusLabel.setStyle("-fx-text-fill: gray; -fx-font-style: italic;");
        
        // Assemblage
        mainLayout.getChildren().addAll(
            connectionTitle,
            connectionPane,
            new Separator(),
            selectionTitle,
            studyLabel,
            studyComboBox,
            seriesLabel,
            seriesComboBox,
            instanceLabel,
            instanceListView,
            new Separator(),
            importTitle,
            importOptions,
            statusLabel
        );
        
        getDialogPane().setContent(mainLayout);
        getDialogPane().setPrefWidth(600);
        
        // Désactiver le bouton importer au début
        getDialogPane().lookupButton(importButtonType).setDisable(true);
        
        // Activer le bouton importer quand une série est sélectionnée
        seriesComboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            getDialogPane().lookupButton(importButtonType).setDisable(newVal == null);
        });
        
        // Logique d'import
        setResultConverter(dialogButton -> {
            if (dialogButton == importButtonType) {
                return performImport();
            }
            return null;
        });
    }
    
    /**
     * Connexion au serveur Orthanc
     */
    private void connectToOrthanc() {
        String url = urlField.getText().trim();
        String username = authCheckBox.isSelected() ? usernameField.getText().trim() : null;
        String password = authCheckBox.isSelected() ? passwordField.getText() : null;
        
        if (url.isEmpty()) {
            showError("URL vide", "Veuillez entrer l'URL du serveur Orthanc");
            return;
        }
        
        connectButton.setDisable(true);
        statusLabel.setText("Connexion en cours...");
        
        if (username != null && !username.isEmpty()) {
            client = new OrthancClient(url, username, password);
        } else {
            client = new OrthancClient(url);
        }
        
        new Thread(() -> {
            boolean connected = client.testConnection();
            
            Platform.runLater(() -> {
                if (connected) {
                    statusLabel.setText("✓ Connecté à Orthanc");
                    statusLabel.setStyle("-fx-text-fill: green; -fx-font-style: italic;");
                    loadStudies();
                } else {
                    statusLabel.setText("✗ Erreur de connexion");
                    statusLabel.setStyle("-fx-text-fill: red; -fx-font-style: italic;");
                    showError("Erreur de connexion", "Impossible de se connecter à Orthanc");
                    connectButton.setDisable(false);
                }
            });
        }).start();
    }
    
    /**
     * Charge la liste des études
     */
    private void loadStudies() {
        statusLabel.setText("Chargement des études...");
        
        new Thread(() -> {
            try {
                List<OrthancClient.OrthancStudy> studies = client.getStudies();
                
                Platform.runLater(() -> {
                    studyComboBox.setItems(FXCollections.observableArrayList(studies));
                    studyComboBox.setDisable(false);
                    
                    if (!studies.isEmpty()) {
                        statusLabel.setText(studies.size() + " étude(s) trouvée(s)");
                        statusLabel.setStyle("-fx-text-fill: gray; -fx-font-style: italic;");
                    } else {
                        statusLabel.setText("Aucune étude trouvée");
                        statusLabel.setStyle("-fx-text-fill: orange; -fx-font-style: italic;");
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("✗ Erreur lors du chargement");
                    statusLabel.setStyle("-fx-text-fill: red; -fx-font-style: italic;");
                    showError("Erreur", "Erreur lors du chargement des études: " + e.getMessage());
                });
            }
        }).start();
    }
    
    /**
     * Appelé quand une étude est sélectionnée
     */
    private void onStudySelected() {
        OrthancClient.OrthancStudy study = studyComboBox.getValue();
        if (study == null) return;
        
        seriesComboBox.getItems().clear();
        instanceListView.getItems().clear();
        seriesComboBox.setDisable(true);
        instanceListView.setDisable(true);
        
        statusLabel.setText("Chargement des séries...");
        
        new Thread(() -> {
            try {
                List<OrthancClient.OrthancSeries> seriesList = new ArrayList<>();
                for (String seriesId : study.getSeriesIds()) {
                    OrthancClient.OrthancSeries series = client.getSeriesDetails(seriesId);
                    if (series != null && "SM".equals(series.getModality())) {
                        seriesList.add(series);
                    }
                }
                
                Platform.runLater(() -> {
                    seriesComboBox.setItems(FXCollections.observableArrayList(seriesList));
                    seriesComboBox.setDisable(false);
                    statusLabel.setText(seriesList.size() + " série(s) trouvée(s)");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    showError("Erreur", "Erreur lors du chargement des séries: " + e.getMessage());
                });
            }
        }).start();
    }
    
    /**
     * Appelé quand une série est sélectionnée
     */
    private void onSeriesSelected() {
        OrthancClient.OrthancSeries series = seriesComboBox.getValue();
        if (series == null) return;
        
        instanceListView.getItems().clear();
        instanceListView.setItems(FXCollections.observableArrayList(series.getInstanceIds()));
        instanceListView.setDisable(false);
        
        if (!series.getInstanceIds().isEmpty()) {
            instanceListView.getSelectionModel().selectFirst();
        }
        
        statusLabel.setText(series.getInstanceIds().size() + " instance(s) dans cette série");
    }
    
    /**
     * Effectue l'import selon l'option sélectionnée
     */
    private OrthancImportResult performImport() {
        OrthancClient.OrthancSeries series = seriesComboBox.getValue();
        if (series == null) return null;
        
        try {
            if (importSeriesRadio.isSelected()) {
                // Import de toute la série
                return importWholeSeries(series);
            } else {
                // Import d'une seule instance
                String instanceId = instanceListView.getSelectionModel().getSelectedItem();
                if (instanceId == null && !series.getInstanceIds().isEmpty()) {
                    instanceId = series.getInstanceIds().get(0);
                }
                return importSingleInstance(instanceId, series.getSeriesDescription());
            }
        } catch (Exception e) {
            Platform.runLater(() -> {
                showError("Erreur", "Erreur lors de l'import: " + e.getMessage());
            });
            return null;
        }
    }
    
    /**
     * Importe une seule instance via l'endpoint /rendered d'Orthanc (PNG décodé par Orthanc)
     */
    private OrthancImportResult importSingleInstance(String instanceId, String seriesDesc) throws Exception {
        File pngFile = File.createTempFile("orthanc_", ".png");

        try (InputStream is = client.downloadInstanceRendered(instanceId);
             FileOutputStream fos = new FileOutputStream(pngFile)) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
        }

        List<File> files = new ArrayList<>();
        files.add(pngFile);

        return new OrthancImportResult(files, "orthanc_" + instanceId, false);
    }
    
    /**
     * Importe toute une série via le plugin WSI — retourne l'ID de série et le client.
     * Aucun téléchargement ici : les tuiles seront récupérées à la demande par OrthancImageServer.
     */
    private OrthancImportResult importWholeSeries(OrthancClient.OrthancSeries series) {
        String seriesName = series.getSeriesDescription().isEmpty()
                ? "orthanc_series_" + series.getId()
                : series.getSeriesDescription();
        return new OrthancImportResult(series.getId(), client, seriesName);
    }
    
    /**
     * Affiche une alerte d'erreur
     */
    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.initModality(Modality.APPLICATION_MODAL);
        alert.showAndWait();
    }
    
    /**
     * Classe pour retourner le résultat de l'import
     */
    public static class OrthancImportResult {
        private final List<File> dicomFiles;   // pour instance unique
        private final String seriesId;         // pour série WSI
        private final OrthancClient client;    // pour série WSI
        private final String seriesName;
        private final boolean isWholeSeries;

        // Constructeur pour instance unique
        public OrthancImportResult(List<File> dicomFiles, String seriesName, boolean isWholeSeries) {
            this.dicomFiles = dicomFiles;
            this.seriesId = null;
            this.client = null;
            this.seriesName = seriesName;
            this.isWholeSeries = isWholeSeries;
        }

        // Constructeur pour série WSI (pas de téléchargement, tuiles à la demande)
        public OrthancImportResult(String seriesId, OrthancClient client, String seriesName) {
            this.dicomFiles = null;
            this.seriesId = seriesId;
            this.client = client;
            this.seriesName = seriesName;
            this.isWholeSeries = true;
        }

        public List<File> getDicomFiles()  { return dicomFiles; }
        public String getSeriesId()        { return seriesId; }
        public OrthancClient getClient()   { return client; }
        public String getSeriesName()      { return seriesName; }
        public boolean isWholeSeries()     { return isWholeSeries; }
    }
}
