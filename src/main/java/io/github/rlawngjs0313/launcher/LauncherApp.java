package io.github.rlawngjs0313.launcher;

import com.google.gson.*;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.collections.*;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.Modality;
import javafx.util.Duration;
import java.awt.Desktop;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

public final class LauncherApp extends Application {
    private Store store;
    private Models.Settings settings;
    private MicrosoftAuth auth;
    private LauncherUpdater updater;
    private Stage stage;
    private final ObservableList<Models.ServerEntry> servers = FXCollections.observableArrayList();
    private final ListView<Models.ServerEntry> list = new ListView<>(servers);
    private final Map<Models.ServerEntry, JsonObject> information = new ConcurrentHashMap<>();
    private final Map<Models.ServerEntry, JsonObject> statuses = new ConcurrentHashMap<>();
    private final Label state = new Label("서버 상태는 30초마다 자동으로 확인합니다.");
    private final Label account = new Label("로그인하지 않음");
    private final Label selection = new Label("접속할 서버를 선택하세요");
    private final Label selectionHint = new Label("목록에서 서버를 선택한 뒤 접속할 수 있습니다.");
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private final ProgressIndicator indicator = new ProgressIndicator();
    private final Button cancel = new Button("취소");
    private final ScheduledExecutorService refresh = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "server-status-refresh"); t.setDaemon(true); return t; });
    private final ExecutorService pings = Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "server-status"); t.setDaemon(true); return t; });
    private volatile Future<?> active;
    private Dialog<?> activeDialog;

    public static void main(String[] args) { launch(args); }
    @Override public void start(Stage stage) throws Exception {
        this.stage = stage; store = new Store(); settings = store.load(); auth = new MicrosoftAuth(store.root); servers.setAll(settings.servers);
        updater = new LauncherUpdater(store.root);
        Button preferences = button("설정", this::preferences);
        Button updates = button("업데이트 확인", () -> checkUpdates(false));
        Button login = button("Microsoft 로그인", () -> work(() -> {
            var logged = auth.authenticate(BuildInfo.MICROSOFT_CLIENT_ID, this::progress, this::returnFromBrowser); Platform.runLater(() -> account.setText(logged.name()));
        }));
        account.getStyleClass().add("account-name");
        Region accountDot = new Region(); accountDot.getStyleClass().addAll("status-badge", "pending");
        account.textProperty().addListener((observable, old, value) -> accountDot.getStyleClass().setAll("status-badge", value.equals("로그인하지 않음") ? "pending" : "online"));
        HBox identity = new HBox(8, accountDot, account); identity.setAlignment(Pos.CENTER_LEFT);
        Region headerSpace = new Region(); HBox.setHgrow(headerSpace, Priority.ALWAYS);
        login.getStyleClass().add("secondary");
        updates.getStyleClass().add("quiet"); preferences.getStyleClass().add("quiet");
        HBox header = new HBox(10, identity, login, headerSpace, updates, preferences); header.setAlignment(Pos.CENTER_LEFT); header.getStyleClass().add("header");
        list.setCellFactory(view -> new ListCell<>() {
            @Override protected double computePrefWidth(double height) { return 0; }
            @Override protected void updateItem(Models.ServerEntry server, boolean empty) {
                super.updateItem(server, empty);
                if (empty || server == null) { setGraphic(null); setText(null); return; }
                JsonObject info = information.get(server), status = statuses.get(server);
                Label name = new Label(server.name()); name.getStyleClass().add("server-name");
                String detail = server.address();
                if (info != null) detail += "  ·  Minecraft " + info.getAsJsonObject("minecraft").get("version").getAsString() + "  ·  " + (info.getAsJsonObject("loader").get("type").getAsString().equals("vanilla") ? "Vanilla" : "NeoForge") + " " + Net.str(info.getAsJsonObject("loader"), "version", "");
                Label version = new Label(detail); version.getStyleClass().add("server-detail");
                String description = status != null && status.has("description") ? chatText(status.get("description")).strip() : "";
                Label motd = new Label(description.replaceAll("[\\r\\n]+", " · ")); motd.getStyleClass().add("muted");
                motd.setMaxWidth(Double.MAX_VALUE); motd.setVisible(!description.isBlank()); motd.managedProperty().bind(motd.visibleProperty());
                if (!description.isBlank()) motd.setTooltip(new Tooltip(description));
                name.setWrapText(true); version.setWrapText(true);
                VBox labels = new VBox(6, name, version, motd); labels.setMinWidth(0); HBox.setHgrow(labels, Priority.ALWAYS);
                ImageView icon = new ImageView(); icon.setFitHeight(64); icon.setFitWidth(64);
                if (status != null && status.has("favicon")) {
                    String image = status.get("favicon").getAsString();
                    if (image.startsWith("data:image/png;base64,") && image.length() < 200000) try { icon.setImage(new Image(new ByteArrayInputStream(Base64.getDecoder().decode(image.substring(22))), 64, 64, true, true)); } catch (IllegalArgumentException ignored) {}
                }
                Label initial = new Label(server.name().isBlank() ? "S" : server.name().substring(0, server.name().offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT));
                initial.getStyleClass().add("server-initial"); initial.setVisible(icon.getImage() == null || icon.getImage().isError());
                StackPane artwork = new StackPane(initial, icon); artwork.getStyleClass().add("server-artwork"); artwork.setMinSize(64, 64); artwork.setMaxSize(64, 64);
                artwork.setAccessibleText(server.name() + " 서버 아이콘");
                HBox row = new HBox(18, artwork, labels, serverStatus(status)); row.setAlignment(Pos.CENTER_LEFT); row.setMinWidth(0);
                row.prefWidthProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
                    () -> Math.max(0, getWidth() - getInsets().getLeft() - getInsets().getRight()), widthProperty(), insetsProperty()));
                setGraphic(row); setText(null); setAccessibleText(server.name() + ", " + detail);
            }
        });
        list.setAccessibleText("등록된 서버 목록. 방향키로 선택하고 Enter 키로 접속합니다.");
        list.disableProperty().bind(busy);
        Label emptyMark = new Label("+"); emptyMark.getStyleClass().add("empty-mark");
        Label emptyTitle = new Label("첫 서버를 추가해보세요"); emptyTitle.getStyleClass().add("empty-title");
        Label emptyHint = new Label("서버 주소와 동기화 API를 등록하면\n게임 설치와 모드 확인을 이어서 진행할 수 있습니다.");
        emptyHint.getStyleClass().add("muted"); emptyHint.setTextAlignment(TextAlignment.CENTER); emptyHint.setWrapText(true);
        Button firstServer = button("서버 추가", () -> editServer(null)); firstServer.getStyleClass().add("secondary");
        VBox empty = new VBox(14, emptyMark, emptyTitle, emptyHint, firstServer); empty.setAlignment(Pos.CENTER); empty.setMaxWidth(380);
        list.setPlaceholder(empty);
        list.setOnMouseClicked(event -> { if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY && event.getClickCount() == 2 && list.getSelectionModel().getSelectedItem() != null) connect(); });
        list.setOnKeyPressed(event -> { if (event.getCode() == KeyCode.ENTER && list.getSelectionModel().getSelectedItem() != null) { connect(); event.consume(); } });
        Button add = button("서버 추가", () -> editServer(null));
        add.getStyleClass().add("secondary");
        Label heading = new Label("내 서버"); heading.getStyleClass().add("section-title");
        Label count = new Label(); count.textProperty().bind(javafx.beans.binding.Bindings.size(servers).asString("%d개")); count.getStyleClass().add("count");
        Region headingSpace = new Region(); HBox.setHgrow(headingSpace, Priority.ALWAYS);
        HBox libraryHeading = new HBox(12, heading, count, headingSpace, add); libraryHeading.setAlignment(Pos.CENTER_LEFT);
        Label libraryHint = new Label("함께 플레이할 서버를 선택하세요."); libraryHint.getStyleClass().add("muted");
        VBox library = new VBox(10, libraryHeading, libraryHint, list); library.getStyleClass().add("library"); VBox.setVgrow(list, Priority.ALWAYS);
        Button edit = button("서버 수정", () -> { var selected = list.getSelectionModel().getSelectedItem(); if (selected != null) editServer(selected); });
        edit.getStyleClass().add("quiet");
        Button remove = button("목록에서 삭제", () -> {
            var selected = list.getSelectionModel().getSelectedItem(); if (selected == null) return;
            if (confirm("서버 목록에서 삭제", selected.name() + "을 목록에서 삭제할까요? 게임 파일은 유지됩니다.")) {
                servers.remove(selected); settings.servers = new ArrayList<>(servers); persist();
                information.remove(selected); statuses.remove(selected);
            }
        });
        remove.getStyleClass().addAll("quiet", "danger");
        Button play = button("서버 접속", this::connect); play.getStyleClass().add("primary"); play.setMinWidth(160);
        for (Button selectedAction : List.of(edit, remove, play)) selectedAction.disableProperty().bind(busy.or(list.getSelectionModel().selectedItemProperty().isNull()));
        selection.getStyleClass().add("selection-title"); selection.setMaxWidth(Double.MAX_VALUE);
        selectionHint.getStyleClass().add("muted"); selectionHint.setWrapText(true);
        VBox selectedInfo = new VBox(5, selection, selectionHint); selectedInfo.setMinWidth(0); HBox.setHgrow(selectedInfo, Priority.ALWAYS);
        HBox selectionActions = new HBox(4, edit, remove); selectionActions.setAlignment(Pos.CENTER_LEFT);
        selectionActions.visibleProperty().bind(list.getSelectionModel().selectedItemProperty().isNotNull());
        HBox launch = new HBox(20, selectedInfo, play); launch.setAlignment(Pos.CENTER_LEFT);
        VBox launchPanel = new VBox(8, launch, selectionActions); launchPanel.getStyleClass().add("launch-panel");
        list.getSelectionModel().selectedItemProperty().addListener((observable, old, selected) -> updateSelection());
        indicator.setPrefSize(16, 16); indicator.setMaxSize(16, 16); indicator.visibleProperty().bind(busy); indicator.managedProperty().bind(busy);
        cancel.getStyleClass().add("quiet"); cancel.visibleProperty().bind(busy); cancel.managedProperty().bind(busy);
        cancel.setOnAction(event -> { Future<?> task = active; if (task != null) task.cancel(true); if (activeDialog != null) activeDialog.close(); });
        state.getStyleClass().add("status-text"); state.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(state, Priority.ALWAYS);
        state.textProperty().addListener((observable, old, value) -> state.setTooltip(new Tooltip(value)));
        Label version = new Label("v" + BuildInfo.VERSION); version.getStyleClass().add("version");
        HBox progress = new HBox(10, indicator, state, cancel, version); progress.setAlignment(Pos.CENTER_LEFT); progress.getStyleClass().add("status-bar");
        VBox root = new VBox(header, library, launchPanel, progress); VBox.setVgrow(library, Priority.ALWAYS);
        Scene scene = new Scene(root, 1040, 720);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/launcher.css")).toExternalForm());
        stage.setTitle("Game Launcher"); stage.setScene(scene); stage.setMinWidth(850); stage.setMinHeight(600); stage.show();
        refresh.scheduleWithFixedDelay(() -> Platform.runLater(this::refreshServers), 0, 30, TimeUnit.SECONDS);
        if (!GameInstaller.os().equals("windows")) progress("개발 환경입니다. 배포 대상은 Windows 10 이상 x64입니다.");
        acknowledgeUpdate();
        if (settings.checkForUpdates && !getParameters().getRaw().contains("--skip-update-check")) Platform.runLater(() -> checkUpdates(true));
    }

    private void updateSelection() {
        Models.ServerEntry server = list.getSelectionModel().getSelectedItem();
        selection.setText(server == null ? "접속할 서버를 선택하세요" : server.name());
        selection.setTooltip(server == null ? null : new Tooltip(server.name()));
        selectionHint.setText(server == null ? "목록에서 서버를 선택한 뒤 접속할 수 있습니다." : "게임 설치 · 모드 확인 후 접속합니다. Enter 키로도 접속할 수 있습니다.");
    }

    private static VBox serverStatus(JsonObject status) {
        boolean online = status != null && status.has("online") && status.get("online").getAsBoolean();
        String availability = status == null ? "상태 확인 중" : online ? "온라인" : "오프라인";
        Region badge = new Region(); badge.getStyleClass().addAll("status-badge", status == null ? "pending" : online ? "online" : "offline");
        badge.setAccessibleText(availability); Tooltip.install(badge, new Tooltip(availability));
        JsonObject players = online ? status.getAsJsonObject("players") : null;
        String count = players != null && players.has("online") && players.has("max")
            ? players.get("online").getAsInt() + " / " + players.get("max").getAsInt() + "명" : "인원 정보 없음";
        Label population = new Label(count); population.getStyleClass().add("muted"); population.setMinWidth(Region.USE_PREF_SIZE);
        population.setAccessibleText("접속 인원 " + count);
        int latency = online && status.has("latencyMs") && !status.get("latencyMs").isJsonNull() ? status.get("latencyMs").getAsInt() : -1;
        HBox signal = new HBox(2); signal.setAlignment(Pos.BOTTOM_CENTER); signal.getStyleClass().add("signal");
        int strength = latency < 0 ? 0 : latency < 80 ? 5 : latency < 150 ? 4 : latency < 300 ? 3 : latency < 600 ? 2 : 1;
        for (int i = 0; i < 5; i++) {
            Region bar = new Region(); bar.getStyleClass().addAll("signal-bar", i < strength ? "active" : "inactive");
            bar.setMinSize(4, 4 + i * 3); bar.setPrefSize(4, 4 + i * 3); bar.setMaxSize(4, 4 + i * 3);
            signal.getChildren().add(bar);
        }
        String response = latency >= 0 ? "응답시간 " + latency + " ms (API 서버 기준)" : availability + " · 응답시간 정보 없음";
        signal.setAccessibleText(response); Tooltip.install(signal, new Tooltip(response));
        Label availabilityLabel = new Label(availability); availabilityLabel.getStyleClass().add("availability");
        HBox availabilityRow = new HBox(7, badge, availabilityLabel); availabilityRow.setAlignment(Pos.CENTER_RIGHT);
        HBox connectionRow = new HBox(10, population, signal); connectionRow.setAlignment(Pos.CENTER_RIGHT);
        VBox summary = new VBox(10, availabilityRow, connectionRow); summary.setAlignment(Pos.CENTER_RIGHT); summary.setMinWidth(Region.USE_PREF_SIZE);
        summary.setAccessibleText(availability + ", 접속 인원 " + count + ", " + response);
        return summary;
    }

    private static String chatText(JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        if (value.isJsonPrimitive()) return value.getAsString().replaceAll("§.", "");
        if (value.isJsonArray()) return String.join("", value.getAsJsonArray().asList().stream().map(LauncherApp::chatText).toList());
        JsonObject object = value.getAsJsonObject();
        return Net.str(object, "text", Net.str(object, "translate", "")) + (object.has("extra") ? chatText(object.get("extra")) : "");
    }

    private Button button(String label, Runnable action) { Button button = new Button(label); button.disableProperty().bind(busy); button.setOnAction(event -> { if (!busy.get()) action.run(); }); return button; }
    private void persist() { try { store.save(settings); } catch (IOException ex) { error(ex); } }
    private void progress(String message) { Platform.runLater(() -> state.setText(message)); }
    private void returnFromBrowser() {
        Platform.runLater(() -> {
            PauseTransition delay = new PauseTransition(Duration.millis(700));
            delay.setOnFinished(event -> {
                if (!stage.isShowing()) return;
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) Desktop.getDesktop().requestForeground(true);
                stage.setIconified(false); stage.toFront();
                Stage target = Window.getWindows().stream()
                    .filter(window -> window instanceof Stage child && child.isShowing() && child.getOwner() == stage && child.getModality() != Modality.NONE)
                    .map(window -> (Stage) window).reduce((first, last) -> last).orElse(stage);
                target.toFront(); target.requestFocus();
            });
            delay.play();
        });
    }
    private void error(Throwable error) {
        if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> error(error)); return; }
        Throwable actual = error; while ((actual instanceof ExecutionException || actual instanceof CompletionException) && actual.getCause() != null) actual = actual.getCause();
        String message = actual.getMessage() == null ? actual.getClass().getSimpleName() : actual.getMessage();
        state.setText("작업을 완료하지 못했습니다.");
        TextArea details = new TextArea(message); details.setEditable(false); details.setWrapText(true);
        details.positionCaret(0);
        details.setPrefRowCount(12); details.setPrefColumnCount(64); details.setMinHeight(120); details.setMaxHeight(Double.MAX_VALUE);
        details.getStyleClass().add("error-details"); details.setAccessibleText("오류 세부 내용");
        ButtonType copy = new ButtonType("내용 복사", ButtonBar.ButtonData.LEFT);
        ButtonType close = new ButtonType("닫기", ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.ERROR, "", copy, close); alert.initOwner(stage); alert.setTitle("오류"); alert.setHeaderText("작업을 완료하지 못했습니다");
        alert.getDialogPane().setContent(details); alert.getDialogPane().setPrefWidth(680); alert.setResizable(true); styleDialog(alert);
        alert.getDialogPane().lookupButton(copy).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            ClipboardContent content = new ClipboardContent(); content.putString(message); Clipboard.getSystemClipboard().setContent(content); event.consume();
        });
        alert.showAndWait();
    }
    private boolean confirm(String title, String text) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL); alert.initOwner(stage); alert.setHeaderText(title); styleDialog(alert);
        activeDialog = alert; try { return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK; } finally { activeDialog = null; }
    }
    private <T> T onUi(Supplier<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> { if (result.isCancelled()) return; try { result.complete(action.get()); } catch (Throwable ex) { result.completeExceptionally(ex); } });
        try { return result.get(); } catch (InterruptedException ex) { result.cancel(false); throw ex; }
    }
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private void work(Work work) {
        if (busy.get()) return;
        busy.set(true);
        FutureTask<Void> task = new FutureTask<>(() -> {
            try { work.run(); }
            catch (InterruptedException | CancellationException ex) { progress("작업이 취소되었습니다."); }
            catch (Exception ex) { Platform.runLater(() -> error(ex)); }
            return null;
        });
        active = task;
        Thread.ofVirtual().name("launcher-operation").start(() -> {
            try { task.run(); }
            finally { Platform.runLater(() -> busy.set(false)); }
        });
    }

    private void refreshServers() {
        for (var server : List.copyOf(servers)) pings.submit(() -> {
            try { information.put(server, Net.api(server, Net.serverPath(server), null)); } catch (Exception ignored) {}
            try { statuses.put(server, Net.api(server, Net.serverPath(server) + "/status", null)); }
            catch (Exception ignored) { JsonObject offline = new JsonObject(); offline.addProperty("online", false); statuses.put(server, offline); }
            Platform.runLater(list::refresh);
        });
    }
    private void editServer(Models.ServerEntry original) {
        Dialog<Models.ServerEntry> dialog = new Dialog<>(); dialog.initOwner(stage); dialog.setTitle(original == null ? "서버 추가" : "서버 수정");
        dialog.setHeaderText(original == null ? "새 서버 등록" : "서버 정보 수정");
        TextField name = new TextField(original == null ? "" : original.name());
        name.setPromptText("목록에 표시할 서버 이름");
        TextField address = new TextField(original == null ? "" : original.address()); address.setPromptText("mc.example.com:25565");
        TextField api = new TextField(original == null ? "" : original.apiBase()); api.setPromptText("https://launcher.example.com");
        TextField id = new TextField(original == null ? "ars-server" : original.serverId());
        GridPane grid = form(new String[]{"표시 이름", "게임 접속 주소", "동기화 API 주소", "서버 ID"}, name, address, api, id);
        List<TextField> fields = List.of(name, address, api, id);
        List<Label> errors = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            Label error = new Label(); error.getStyleClass().add("field-error"); error.setWrapText(true);
            error.setVisible(false); error.managedProperty().bind(error.visibleProperty()); errors.add(error);
            grid.add(error, 0, i * 3 + 2);
        }
        Label hint = new Label("게임 접속 주소와 동기화 API 주소는 서버 관리자에게 확인하세요."); hint.setWrapText(true); hint.getStyleClass().add("muted"); hint.setMaxWidth(440);
        dialog.getDialogPane().setContent(new VBox(16, grid, hint));
        ButtonType save = new ButtonType("서버 저장", ButtonBar.ButtonData.OK_DONE);
        ButtonType dismiss = new ButtonType("취소", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(save, dismiss); styleDialog(dialog);
        dialog.getDialogPane().lookupButton(save).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            String[] messages = {name.getText().isBlank() ? "목록에 표시할 서버 이름을 입력하세요." : "", address.getText().isBlank() ? "게임 접속 주소를 입력하세요." : "", "", id.getText().strip().matches("[A-Za-z0-9_-]+") ? "" : "서버 ID는 영문, 숫자, 밑줄, 하이픈으로 입력하세요."};
            try { Net.apiBase(api.getText().strip()); } catch (Exception ex) { messages[2] = "HTTPS API 주소를 입력하세요. localhost 개발 주소만 HTTP를 사용할 수 있습니다."; }
            TextField firstInvalid = null;
            for (int i = 0; i < messages.length; i++) {
                errors.get(i).setText(messages[i]); errors.get(i).setVisible(!messages[i].isEmpty());
                fields.get(i).setAccessibleHelp(messages[i]);
                fields.get(i).pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("invalid"), !messages[i].isEmpty());
                if (!messages[i].isEmpty() && firstInvalid == null) firstInvalid = fields.get(i);
            }
            if (firstInvalid != null) { event.consume(); firstInvalid.requestFocus(); resizeDialog(dialog); }
        });
        dialog.setResultConverter(button -> button == save ? new Models.ServerEntry(name.getText().strip(), address.getText().strip(), api.getText().strip().replaceAll("/$", ""), id.getText().strip()) : null);
        dialog.showAndWait().ifPresent(server -> work(() -> {
            if (server.name().isBlank() || server.address().isBlank() || !server.serverId().matches("[A-Za-z0-9_-]+")) throw new IOException("이름, 게임 주소, 서버 ID를 확인하세요.");
            Net.apiBase(server.apiBase());
            JsonObject info = Net.api(server, Net.serverPath(server), null);
            onUi(() -> {
                if (original == null) servers.add(server); else servers.set(servers.indexOf(original), server);
                settings.servers = new ArrayList<>(servers); persist(); information.put(server, info); list.getSelectionModel().select(server); refreshServers(); return null;
            });
            progress("서버를 저장했습니다.");
        }));
    }
    private static GridPane form(String[] labels, Control... controls) {
        GridPane grid = new GridPane(); grid.setVgap(7);
        for (int i = 0; i < labels.length; i++) {
            Label label = new Label(labels[i]); label.getStyleClass().add("field-label"); label.setLabelFor(controls[i]);
            grid.add(label, 0, i * 3); grid.add(controls[i], 0, i * 3 + 1); controls[i].setPrefWidth(440);
            GridPane.setMargin(label, new Insets(i == 0 ? 0 : 10, 0, 0, 0));
        }
        return grid;
    }
    private void styleDialog(Dialog<?> dialog) {
        dialog.getDialogPane().getStylesheets().add(Objects.requireNonNull(getClass().getResource("/launcher.css")).toExternalForm());
        var content = dialog.getDialogPane().getContent();
        if (content != null) {
            VBox body = new VBox(content); body.getStyleClass().add("dialog-body"); body.setMinWidth(0); VBox.setVgrow(content, Priority.ALWAYS);
            dialog.getDialogPane().setContent(body);
        }
        dialog.getDialogPane().getButtonTypes().forEach(type -> {
            if (type.getButtonData().isDefaultButton()) dialog.getDialogPane().lookupButton(type).getStyleClass().add("primary");
        });
    }
    private static void resizeDialog(Dialog<?> dialog) {
        if (dialog.getDialogPane().getScene().getWindow() instanceof Stage window) window.sizeToScene();
    }
    private void preferences() {
        Dialog<ButtonType> dialog = new Dialog<>(); dialog.initOwner(stage); dialog.setTitle("런처 설정");
        dialog.setHeaderText("게임 실행 설정");
        TextField javaPath = new TextField(settings.javaPath); javaPath.setPromptText("비워두면 자동 설치 · 직접 지정 시 java.exe 전체 경로");
        Spinner<Integer> memory = new Spinner<>(1024, 32768, settings.memoryMb, 512); memory.setEditable(true);
        GridPane grid = form(new String[]{"게임 Java 실행 파일", "게임 메모리 (MB)"}, javaPath, memory);
        Button logout = new Button("로그아웃"); logout.setOnAction(event -> { try { auth.logout(); account.setText("로그인하지 않음"); } catch (IOException ex) { error(ex); } });
        Button folder = new Button("데이터 폴더 열기"); folder.setOnAction(event -> { try { Desktop.getDesktop().open(store.root.toFile()); } catch (IOException ex) { error(ex); } });
        CheckBox automaticUpdates = new CheckBox("런처 시작 시 새 버전 확인"); automaticUpdates.setSelected(settings.checkForUpdates);
        Label hint = new Label("Minecraft·NeoForge 설치 파일과 모드는 서버별로 관리합니다."); hint.getStyleClass().add("muted"); hint.setWrapText(true);
        VBox content = new VBox(18, grid, automaticUpdates, new HBox(10, logout, folder), hint);
        ButtonType save = new ButtonType("설정 저장", ButtonBar.ButtonData.OK_DONE);
        ButtonType dismiss = new ButtonType("취소", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().setContent(content); dialog.getDialogPane().getButtonTypes().addAll(save, dismiss); styleDialog(dialog);
        Label memoryError = new Label(); memoryError.getStyleClass().add("field-error"); memoryError.setWrapText(true); memoryError.setVisible(false); memoryError.managedProperty().bind(memoryError.visibleProperty());
        grid.add(memoryError, 0, 5);
        dialog.getDialogPane().lookupButton(save).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try {
                int value = Integer.parseInt(memory.getEditor().getText().strip());
                if (value < 1024 || value > 32768) throw new NumberFormatException();
                memory.getValueFactory().setValue(value); memoryError.setVisible(false);
            } catch (NumberFormatException ex) {
                memoryError.setText("메모리는 1024~32768 MB 사이의 숫자로 입력하세요."); memoryError.setVisible(true); memory.getEditor().requestFocus(); event.consume(); resizeDialog(dialog);
            }
        });
        if (dialog.showAndWait().orElse(dismiss) == save) { settings.javaPath = javaPath.getText().strip(); settings.memoryMb = memory.getValue(); settings.checkForUpdates = automaticUpdates.isSelected(); persist(); }
    }

    private void acknowledgeUpdate() throws IOException {
        for (String argument : getParameters().getRaw()) if (argument.startsWith("--update-receipt=")) {
            Path receipt = Path.of(argument.substring("--update-receipt=".length())).toAbsolutePath().normalize();
            if (!receipt.startsWith(store.root.resolve("updates").toAbsolutePath().normalize()) || !receipt.getFileName().toString().equals("ready.marker")) throw new IOException("잘못된 업데이트 확인 경로");
            Store.write(receipt, BuildInfo.VERSION);
        }
    }
    private void checkUpdates(boolean automatic) {
        work(() -> {
            LauncherUpdater.Release release;
            try { updater.cleanupOldJobs(); release = updater.latest(); }
            catch (Exception ex) {
                if (!automatic) throw ex;
                progress("업데이트를 확인하지 못했습니다. 기존 버전으로 계속 사용할 수 있습니다."); return;
            }
            if (release == null) { if (!automatic) onUi(() -> { Alert alert = new Alert(Alert.AlertType.INFORMATION, "현재 버전: " + BuildInfo.VERSION + "\n다운로드할 새 안정 릴리즈가 없습니다.", ButtonType.OK); alert.initOwner(stage); alert.setHeaderText("업데이트 확인"); styleDialog(alert); alert.showAndWait(); return null; }); return; }
            boolean accepted = onUi(() -> {
                ButtonType update = new ButtonType("업데이트 후 재시작", ButtonBar.ButtonData.OK_DONE);
                ButtonType later = new ButtonType("나중에", ButtonBar.ButtonData.CANCEL_CLOSE);
                String text = BuildInfo.VERSION + " → " + release.version() + "\n새 설치 파일: " + Math.round(release.size() / 1024.0 / 1024.0) + " MB\n필요하면 복구용 현재 버전 설치 파일도 받습니다.\n설정·계정·게임 파일은 유지됩니다.\n\n" + release.notes();
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION, text, update, later); alert.initOwner(stage); alert.setHeaderText("새 Game Launcher 버전이 있습니다");
                styleDialog(alert);
                alert.getDialogPane().setPrefWidth(650);
                activeDialog = alert; try { return alert.showAndWait().orElse(later) == update; } finally { activeDialog = null; }
            });
            if (!accepted) return;
            Path instances = store.root.resolve("instances");
            if (Files.isDirectory(instances)) try (var paths = Files.list(instances)) { for (Path instance : paths.filter(Files::isDirectory).toList()) checkRunning(instance); }
            updater.install(release, this::progress);
            Platform.runLater(Platform::exit);
        });
    }

    private void connect() {
        if (busy.get()) return;
        Models.ServerEntry server = list.getSelectionModel().getSelectedItem(); if (server == null) return;
        work(() -> {
            MicrosoftAuth.validateClientId(BuildInfo.MICROSOFT_CLIENT_ID);
            Path instance = store.instance(server);
            FileChannel channel = FileChannel.open(instance.resolve("launcher.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = null; boolean handedOff = false;
            try {
                lock = channel.tryLock(); if (lock == null) throw new IOException("이 서버의 설치 또는 실행 작업이 이미 진행 중입니다.");
                checkRunning(instance);
                progress("서버 요구사항 확인");
                JsonObject info = Net.api(server, Net.serverPath(server), null);
                if (!info.has("schemaVersion") || info.get("schemaVersion").getAsInt() != 1) throw new IOException("지원하지 않는 서버 API 버전");
                String mc = info.getAsJsonObject("minecraft").get("version").getAsString();
                String loader = info.getAsJsonObject("loader").get("type").getAsString();
                if (!Set.of("vanilla", "neoforge").contains(loader)) throw new IOException("지원하지 않는 로더: " + loader);
                boolean installed = false;
                if (Files.isRegularFile(instance.resolve("instance.json"))) {
                    JsonObject previous = GameInstaller.read(instance.resolve("instance.json"));
                    installed = previous.get("minecraft").equals(info.get("minecraft")) && previous.get("loader").equals(info.get("loader"))
                        && Files.isRegularFile(instance.resolve("game/versions").resolve(mc).resolve(mc + ".jar"));
                }
                if (!installed && !onUi(() -> confirm("게임 설치 확인", "Minecraft " + mc + "\n" + loader + " " + Net.str(info.getAsJsonObject("loader"), "version", "") + "\n필요한 게임·로더·Java 파일을 확인하고 설치할까요?"))) return;
                GameInstaller installer = new GameInstaller(instance.resolve("game"), this::progress);
                GameInstaller.Installed game = installer.ensure(info, settings);
                progress("설치된 모드 확인");
                ModSync.Plan plan = ModSync.prepare(server, info, instance);
                if (plan.reviewRequired() && !onUi(() -> selectMods(plan))) return;
                ModSync.apply(server, plan, instance, this::progress);
                var player = auth.authenticate(BuildInfo.MICROSOFT_CLIENT_ID, this::progress, this::returnFromBrowser);
                Net.checkCancelled();
                checkRunning(instance);
                progress("게임 실행 및 서버 접속");
                Process process = installer.launch(game, instance, server.address(), player, settings.memoryMb);
                JsonObject marker = new JsonObject(); marker.addProperty("pid", process.pid()); marker.addProperty("startedAt", process.info().startInstant().orElse(Instant.now()).toString());
                Store.write(instance.resolve("game-process.json"), Store.JSON.toJson(marker));
                FileLock runningLock = lock;
                Thread.ofVirtual().name("game-exit-watcher").start(() -> {
                    try {
                        int exit = process.waitFor();
                        if (exit == 0) progress("게임이 종료되었습니다.");
                        else error(new IOException("게임이 비정상 종료되었습니다. (종료 코드: " + exit + ")\n로그 파일: " + instance.resolve("logs/launcher-game.log")));
                    }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                    finally { try { Files.deleteIfExists(instance.resolve("game-process.json")); runningLock.release(); channel.close(); } catch (IOException ignored) {} }
                });
                handedOff = true;
                Platform.runLater(() -> account.setText(player.name()));
            } finally { if (!handedOff) { if (lock != null) lock.release(); channel.close(); } }
        });
    }
    private static void checkRunning(Path instance) throws IOException {
        Path marker = instance.resolve("game-process.json"); if (!Files.isRegularFile(marker)) return;
        JsonObject running = GameInstaller.read(marker);
        var handle = ProcessHandle.of(running.get("pid").getAsLong());
        if (handle.isPresent() && handle.get().isAlive() && handle.get().info().startInstant().map(t -> t.toString().equals(running.get("startedAt").getAsString())).orElse(true)) throw new IOException("이 서버의 게임이 실행 중입니다. 게임을 종료한 뒤 다시 시도하세요.");
    }
    private boolean selectMods(ModSync.Plan plan) {
        Dialog<Boolean> dialog = new Dialog<>(); dialog.initOwner(stage); dialog.setTitle("모드 선택");
        Label description = new Label("유지할 모드 파일만 체크하세요. 선택 해제한 기존 파일은 백업 없이 삭제됩니다."); description.setWrapText(true);
        VBox rows = new VBox(12);
        for (var candidate : plan.candidates()) {
            CheckBox check = new CheckBox(candidate.toString()); check.setSelected(candidate.selected); check.setWrapText(true);
            check.selectedProperty().addListener((observable, old, selected) -> candidate.selected = selected); rows.getChildren().add(check);
        }
        ScrollPane scroll = new ScrollPane(rows); scroll.setFitToWidth(true); scroll.setPrefViewportHeight(330);
        Label constraints = new Label("필수 조건:\n" + String.join("\n", plan.info().getAsJsonArray("requirements").asList().stream().map(e -> e.getAsJsonObject().get("id").getAsString() + " " + Net.array(e.getAsJsonObject(), "ranges")).toList())); constraints.setWrapText(true);
        Label validation = new Label(); validation.setWrapText(true); validation.getStyleClass().add("field-error");
        VBox box = new VBox(14, description, constraints, scroll, validation); box.setPrefWidth(800);
        dialog.getDialogPane().setContent(box); dialog.getDialogPane().getButtonTypes().addAll(ButtonType.APPLY, ButtonType.CANCEL);
        styleDialog(dialog);
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.APPLY)).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            List<String> errors = ModSync.validate(plan.info(), plan.candidates()); if (!errors.isEmpty()) { validation.setText(String.join("\n", errors)); event.consume(); }
        });
        dialog.setResultConverter(button -> button == ButtonType.APPLY);
        activeDialog = dialog; try { return dialog.showAndWait().orElse(false); } finally { activeDialog = null; }
    }
    @Override public void stop() { refresh.shutdownNow(); pings.shutdownNow(); if (active != null) active.cancel(true); }
}
