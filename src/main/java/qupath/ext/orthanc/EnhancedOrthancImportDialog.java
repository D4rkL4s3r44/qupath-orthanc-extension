package qupath.ext.orthanc;

import qupath.ext.orthanc.Messages;
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

import qupath.lib.gui.prefs.PathPrefs;
import javafx.beans.property.StringProperty;
import javafx.beans.property.BooleanProperty;

/**
 * Dialog for importing DICOM images from Orthanc.
 * Supports single instance import and full WSI series import.
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

    // Persistent: These will be remembered next time QuPath is opened
    private static final StringProperty PREF_URL = PathPrefs.createPersistentPreference("orthanc.url", "http://localhost:8042");
    private static final StringProperty PREF_USERNAME = PathPrefs.createPersistentPreference("orthanc.username", "");
    private static final BooleanProperty PREF_USE_AUTH = PathPrefs.createPersistentPreference("orthanc.use.auth", false);

    // Transient: This stays in memory for the current session only
    private static String orthancPasswString = "";

    public EnhancedOrthancImportDialog() {
        setTitle(Messages.get("dialog.title"));
        setHeaderText(Messages.get("dialog.header"));

        ButtonType importButtonType = new ButtonType(Messages.get("dialog.import"), ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(importButtonType, ButtonType.CANCEL);

        VBox mainLayout = new VBox(15);
        mainLayout.setPadding(new Insets(10));

        // === Section 1: Connection ===
        Label connectionTitle = new Label(Messages.get("section.connection"));
        connectionTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        GridPane connectionPane = new GridPane();
        connectionPane.setHgap(10);
        connectionPane.setVgap(10);

        Label urlLabel = new Label(Messages.get("field.url"));
        urlField = new TextField(PREF_URL.get());
        urlField.setPrefWidth(300);

        authCheckBox = new CheckBox(Messages.get("field.auth"));
        authCheckBox.setSelected(PREF_USE_AUTH.get());

        Label usernameLabel = new Label(Messages.get("field.username"));
        usernameField = new TextField(PREF_USERNAME.get());

        Label passwordLabel = new Label(Messages.get("field.password"));
        passwordField = new PasswordField();
        passwordField.setText(orthancPasswString);

        if (authCheckBox.isSelected() == false) {
            usernameField.setDisable(true);
            passwordField.setDisable(true);
        }

        authCheckBox.setOnAction(e -> {
            boolean selected = authCheckBox.isSelected();
            usernameField.setDisable(!selected);
            passwordField.setDisable(!selected);
        });

        connectButton = new Button(Messages.get("field.connect"));
        connectButton.setOnAction(e -> connectToOrthanc());

        connectionPane.add(urlLabel,      0, 0);
        connectionPane.add(urlField,      1, 0);
        connectionPane.add(authCheckBox,  1, 1);
        connectionPane.add(usernameLabel, 0, 2);
        connectionPane.add(usernameField, 1, 2);
        connectionPane.add(passwordLabel, 0, 3);
        connectionPane.add(passwordField, 1, 3);
        connectionPane.add(connectButton, 1, 4);

        // === Section 2: Selection ===
        Label selectionTitle = new Label(Messages.get("section.selection"));
        selectionTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        Label studyLabel = new Label(Messages.get("field.study"));
        studyComboBox = new ComboBox<>();
        studyComboBox.setMaxWidth(Double.MAX_VALUE);
        studyComboBox.setDisable(true);
        studyComboBox.setOnAction(e -> onStudySelected());

        Label seriesLabel = new Label(Messages.get("field.series"));
        seriesComboBox = new ComboBox<>();
        seriesComboBox.setMaxWidth(Double.MAX_VALUE);
        seriesComboBox.setDisable(true);
        seriesComboBox.setOnAction(e -> onSeriesSelected());

        Label instanceLabel = new Label(Messages.get("field.instances"));
        instanceListView = new ListView<>();
        instanceListView.setPrefHeight(100);
        instanceListView.setDisable(true);
        instanceListView.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);

        // === Section 3: Import options ===
        Label importTitle = new Label(Messages.get("section.options"));
        importTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        ToggleGroup importGroup = new ToggleGroup();

        importSingleRadio = new RadioButton(Messages.get("radio.single"));
        importSingleRadio.setToggleGroup(importGroup);
        importSingleRadio.setSelected(true);

        importSeriesRadio = new RadioButton(Messages.get("radio.series"));
        importSeriesRadio.setToggleGroup(importGroup);

        VBox importOptions = new VBox(5);
        importOptions.getChildren().addAll(importSingleRadio, importSeriesRadio);

        statusLabel = new Label("");
        statusLabel.setStyle("-fx-text-fill: gray; -fx-font-style: italic;");

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
        getDialogPane().lookupButton(importButtonType).setDisable(true);

        seriesComboBox.valueProperty().addListener((obs, oldVal, newVal) ->
            getDialogPane().lookupButton(importButtonType).setDisable(newVal == null));

        setResultConverter(dialogButton -> {
            if (dialogButton == importButtonType) return performImport();
            return null;
        });
    }

    private void connectToOrthanc() {
        String url = urlField.getText().trim();
        boolean useAuth = authCheckBox.isSelected();
        String username = useAuth ? usernameField.getText().trim() : null;
        String password = useAuth ? passwordField.getText() : null;


        if (url.isEmpty()) {
            showError(Messages.get("error.emptyUrl"), Messages.get("error.emptyUrlContent"));
            return;
        }

        connectButton.setDisable(true);
        statusLabel.setText(Messages.get("status.connecting"));

        if (username != null && !username.isEmpty()) {
            client = new OrthancClient(url, username, password);
        } else {
            client = new OrthancClient(url);
        }

        new Thread(() -> {
            boolean connected = client.testConnection();
            Platform.runLater(() -> {
                if (connected) {
                    // Update preferences on successful connection
                    PREF_URL.set(url);
                    PREF_USE_AUTH.set(useAuth);
                
                    if (useAuth) {
                        PREF_USERNAME.set(username);
                        orthancPasswString = password;
                    }

                    statusLabel.setText(Messages.get("status.connected"));
                    statusLabel.setStyle("-fx-text-fill: green; -fx-font-style: italic;");
                    loadStudies();
                } else {
                    statusLabel.setText(Messages.get("status.connectionError"));
                    statusLabel.setStyle("-fx-text-fill: red; -fx-font-style: italic;");
                    showError(Messages.get("error.connection"), Messages.get("error.connectionContent"));
                    connectButton.setDisable(false);
                }
            });
        }).start();
    }

    private void loadStudies() {
        statusLabel.setText(Messages.get("status.loadingStudies"));

        new Thread(() -> {
            try {
                List<OrthancClient.OrthancStudy> studies = client.getStudies();
                Platform.runLater(() -> {
                    studyComboBox.setItems(FXCollections.observableArrayList(studies));
                    studyComboBox.setDisable(false);
                    if (!studies.isEmpty()) {
                        statusLabel.setText(studies.size() + Messages.get("status.studies"));
                        statusLabel.setStyle("-fx-text-fill: gray; -fx-font-style: italic;");
                    } else {
                        statusLabel.setText(Messages.get("status.noStudies"));
                        statusLabel.setStyle("-fx-text-fill: orange; -fx-font-style: italic;");
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText(Messages.get("status.loadingError"));
                    statusLabel.setStyle("-fx-text-fill: red; -fx-font-style: italic;");
                    showError(Messages.get("error.title"), Messages.get("error.loadStudies") + " " + e.getMessage());
                });
            }
        }).start();
    }

    private void onStudySelected() {
        OrthancClient.OrthancStudy study = studyComboBox.getValue();
        if (study == null) return;

        seriesComboBox.getItems().clear();
        instanceListView.getItems().clear();
        seriesComboBox.setDisable(true);
        instanceListView.setDisable(true);

        statusLabel.setText(Messages.get("status.loadingSeries"));

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
                    statusLabel.setText(seriesList.size() + Messages.get("status.series"));
                });
            } catch (Exception e) {
                Platform.runLater(() ->
                    showError(Messages.get("error.title"), Messages.get("error.loadSeries") + " " + e.getMessage()));
            }
        }).start();
    }

    private void onSeriesSelected() {
        OrthancClient.OrthancSeries series = seriesComboBox.getValue();
        if (series == null) return;

        instanceListView.getItems().clear();
        instanceListView.setItems(FXCollections.observableArrayList(series.getInstanceIds()));
        instanceListView.setDisable(false);

        if (!series.getInstanceIds().isEmpty()) {
            instanceListView.getSelectionModel().selectFirst();
        }

        statusLabel.setText(series.getInstanceIds().size() + Messages.get("status.instances"));
    }

    private OrthancImportResult performImport() {
        OrthancClient.OrthancSeries series = seriesComboBox.getValue();
        if (series == null) return null;

        try {
            if (importSeriesRadio.isSelected()) {
                return importWholeSeries(series);
            } else {
                String instanceId = instanceListView.getSelectionModel().getSelectedItem();
                if (instanceId == null && !series.getInstanceIds().isEmpty()) {
                    instanceId = series.getInstanceIds().get(0);
                }
                return importSingleInstance(instanceId, series.getSeriesDescription());
            }
        } catch (Exception e) {
            Platform.runLater(() ->
                showError(Messages.get("error.title"), Messages.get("error.importDialog") + " " + e.getMessage()));
            return null;
        }
    }

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

    private OrthancImportResult importWholeSeries(OrthancClient.OrthancSeries series) {
        String seriesName = series.getSeriesDescription().isEmpty()
                ? "orthanc_series_" + series.getId()
                : series.getSeriesDescription();
        return new OrthancImportResult(series.getId(), client, seriesName);
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.initModality(Modality.APPLICATION_MODAL);
        alert.showAndWait();
    }

    public static class OrthancImportResult {
        private final List<File> dicomFiles;
        private final String seriesId;
        private final OrthancClient client;
        private final String seriesName;
        private final boolean isWholeSeries;

        public OrthancImportResult(List<File> dicomFiles, String seriesName, boolean isWholeSeries) {
            this.dicomFiles = dicomFiles;
            this.seriesId = null;
            this.client = null;
            this.seriesName = seriesName;
            this.isWholeSeries = isWholeSeries;
        }

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
