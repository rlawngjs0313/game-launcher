package io.github.rlawngjs0313.launcher;

import com.google.gson.*;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.*;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;
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
    private Stage stage;
    private final ObservableList<Models.ServerEntry> servers = FXCollections.observableArrayList();
    private final ListView<Models.ServerEntry> list = new ListView<>(servers);
    private final Map<Models.ServerEntry, JsonObject> information = new ConcurrentHashMap<>();
    private final Map<Models.ServerEntry, JsonObject> statuses = new ConcurrentHashMap<>();
    private final TextArea log = new TextArea();
    private final Label state = new Label("서버를 등록하거나 선택하세요.");
    private final Label account = new Label("로그인하지 않음");
    private final ProgressIndicator indicator = new ProgressIndicator();
    private final Button cancel = new Button("취소");
    private final List<Button> guarded = new ArrayList<>();
    private final ScheduledExecutorService refresh = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "server-status-refresh"); t.setDaemon(true); return t; });
    private final ExecutorService pings = Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "server-status"); t.setDaemon(true); return t; });
    private volatile Future<?> active;
    private Dialog<?> activeDialog;

    public static void main(String[] args) { launch(args); }
    @Override public void start(Stage stage) throws Exception {
        this.stage = stage; store = new Store(); settings = store.load(); auth = new MicrosoftAuth(store.root); servers.setAll(settings.servers);
        Label title = new Label("Game Launcher"); title.getStyleClass().add("title");
        Label subtitle = new Label("함께 플레이할 서버를 선택하세요."); subtitle.getStyleClass().add("muted");
        VBox heading = new VBox(6, title, subtitle); HBox.setHgrow(heading, Priority.ALWAYS);
        Button preferences = button("설정", this::preferences);
        Button login = button("Microsoft 로그인", () -> work(() -> {
            var logged = auth.authenticate(settings.clientId, this::progress); Platform.runLater(() -> account.setText(logged.name()));
        }));
        HBox header = new HBox(16, heading, account, login, preferences); header.setAlignment(Pos.CENTER_LEFT);
        list.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(Models.ServerEntry server, boolean empty) {
                super.updateItem(server, empty);
                if (empty || server == null) { setGraphic(null); setText(null); return; }
                JsonObject info = information.get(server), status = statuses.get(server);
                Label name = new Label(server.name()); name.getStyleClass().add("server-name");
                String detail = server.address();
                if (info != null) detail += "  ·  Minecraft " + info.getAsJsonObject("minecraft").get("version").getAsString() + "  ·  " + (info.getAsJsonObject("loader").get("type").getAsString().equals("vanilla") ? "Vanilla" : "NeoForge") + " " + Net.str(info.getAsJsonObject("loader"), "version", "");
                Label version = new Label(detail); version.getStyleClass().add("muted");
                String online = status == null ? "상태 확인 중" : status.has("online") && status.get("online").getAsBoolean() ? "온라인  " + status.getAsJsonObject("players").get("online").getAsInt() + "/" + status.getAsJsonObject("players").get("max").getAsInt() + "명  ·  " + status.get("latencyMs").getAsInt() + " ms (API 서버 기준)" : "연결할 수 없음";
                Label onlineLabel = new Label(online);
                Label motd = new Label(status != null && status.has("description") ? chatText(status.get("description")) : ""); motd.setWrapText(true);
                VBox labels = new VBox(7, name, version, motd, onlineLabel); labels.setPadding(new Insets(12));
                ImageView icon = new ImageView(); icon.setFitHeight(64); icon.setFitWidth(64);
                if (status != null && status.has("favicon")) {
                    String image = status.get("favicon").getAsString();
                    if (image.startsWith("data:image/png;base64,") && image.length() < 200000) try { icon.setImage(new Image(new ByteArrayInputStream(Base64.getDecoder().decode(image.substring(22))), 64, 64, true, true)); } catch (IllegalArgumentException ignored) {}
                }
                HBox row = new HBox(12, icon, labels); row.setAlignment(Pos.CENTER_LEFT); setGraphic(row); setText(null);
            }
        });
        list.setPlaceholder(new Label("아직 등록된 서버가 없습니다. 아래 ‘서버 추가’를 눌러주세요."));
        list.setOnMouseClicked(event -> { if (event.getClickCount() == 2 && list.getSelectionModel().getSelectedItem() != null) connect(); });
        Button add = button("서버 추가", () -> editServer(null));
        Button edit = button("수정", () -> { var selected = list.getSelectionModel().getSelectedItem(); if (selected != null) editServer(selected); });
        Button remove = button("목록에서 삭제", () -> {
            var selected = list.getSelectionModel().getSelectedItem(); if (selected == null) return;
            if (confirm("서버 목록에서 삭제", selected.name() + "을 목록에서 삭제할까요? 게임 파일은 유지됩니다.")) {
                servers.remove(selected); settings.servers = new ArrayList<>(servers); persist();
            }
        });
        Button play = button("접속", this::connect); play.getStyleClass().add("primary");
        Region space = new Region(); HBox.setHgrow(space, Priority.ALWAYS);
        HBox actions = new HBox(10, add, edit, remove, space, play);
        indicator.setPrefSize(22, 22); indicator.setVisible(false); cancel.setDisable(true);
        cancel.setOnAction(event -> { Future<?> task = active; if (task != null) task.cancel(true); if (activeDialog != null) activeDialog.close(); });
        HBox progress = new HBox(12, indicator, state, cancel); progress.setAlignment(Pos.CENTER_LEFT); HBox.setHgrow(state, Priority.ALWAYS);
        log.setEditable(false); log.setWrapText(true); log.setPrefRowCount(5);
        VBox root = new VBox(18, header, list, actions, progress, log); root.setPadding(new Insets(26)); VBox.setVgrow(list, Priority.ALWAYS);
        Scene scene = new Scene(root, 1040, 720);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/launcher.css")).toExternalForm());
        stage.setTitle("Game Launcher"); stage.setScene(scene); stage.setMinWidth(850); stage.setMinHeight(600); stage.show();
        refresh.scheduleWithFixedDelay(() -> Platform.runLater(this::refreshServers), 0, 30, TimeUnit.SECONDS);
        if (!GameInstaller.os().equals("windows")) progress("개발 환경입니다. 배포 대상은 Windows 10 이상 x64입니다.");
    }

    private static String chatText(JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        if (value.isJsonPrimitive()) return value.getAsString().replaceAll("§.", "");
        if (value.isJsonArray()) return String.join("", value.getAsJsonArray().asList().stream().map(LauncherApp::chatText).toList());
        JsonObject object = value.getAsJsonObject();
        return Net.str(object, "text", Net.str(object, "translate", "")) + (object.has("extra") ? chatText(object.get("extra")) : "");
    }

    private Button button(String label, Runnable action) { Button button = new Button(label); guarded.add(button); button.setOnAction(event -> { if (active == null || active.isDone()) action.run(); }); return button; }
    private void persist() { try { store.save(settings); } catch (IOException ex) { error(ex); } }
    private void progress(String message) { Platform.runLater(() -> { state.setText(message); log.appendText(message + "\n"); }); }
    private void error(Throwable error) {
        Throwable actual = error; while ((actual instanceof ExecutionException || actual instanceof CompletionException) && actual.getCause() != null) actual = actual.getCause();
        String message = actual.getMessage() == null ? actual.getClass().getSimpleName() : actual.getMessage();
        progress("오류: " + message);
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK); alert.initOwner(stage); alert.setHeaderText("작업을 완료하지 못했습니다"); alert.showAndWait();
    }
    private boolean confirm(String title, String text) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL); alert.initOwner(stage); alert.setHeaderText(title);
        activeDialog = alert; try { return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK; } finally { activeDialog = null; }
    }
    private <T> T onUi(Supplier<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> { if (result.isCancelled()) return; try { result.complete(action.get()); } catch (Throwable ex) { result.completeExceptionally(ex); } });
        try { return result.get(); } catch (InterruptedException ex) { result.cancel(false); throw ex; }
    }
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private void work(Work work) {
        if (active != null && !active.isDone()) return;
        guarded.forEach(b -> b.setDisable(true)); indicator.setVisible(true); cancel.setDisable(false);
        FutureTask<Void> task = new FutureTask<>(() -> {
            try { work.run(); }
            catch (InterruptedException | CancellationException ex) { progress("작업이 취소되었습니다."); }
            catch (Exception ex) { Platform.runLater(() -> error(ex)); }
            finally { Platform.runLater(() -> { guarded.forEach(b -> b.setDisable(false)); indicator.setVisible(false); cancel.setDisable(true); }); }
            return null;
        });
        active = task; Thread.ofVirtual().name("launcher-operation").start(task);
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
        TextField name = new TextField(original == null ? "" : original.name());
        TextField address = new TextField(original == null ? "" : original.address()); address.setPromptText("mc.example.com:25565");
        TextField api = new TextField(original == null ? "" : original.apiBase()); api.setPromptText("https://launcher.example.com");
        TextField id = new TextField(original == null ? "ars-server" : original.serverId());
        PasswordField token = new PasswordField(); token.setText(original == null ? "" : original.token());
        GridPane grid = form(new String[]{"표시 이름", "게임 접속 주소", "동기화 API 주소", "서버 ID", "초대 토큰 (선택)"}, name, address, api, id, token);
        dialog.getDialogPane().setContent(grid); dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == ButtonType.OK ? new Models.ServerEntry(name.getText().strip(), address.getText().strip(), api.getText().strip().replaceAll("/$", ""), id.getText().strip(), token.getText().strip()) : null);
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
        GridPane grid = new GridPane(); grid.setHgap(14); grid.setVgap(14); grid.setPadding(new Insets(12));
        for (int i = 0; i < labels.length; i++) { grid.add(new Label(labels[i]), 0, i); grid.add(controls[i], 1, i); controls[i].setPrefWidth(390); }
        return grid;
    }
    private void preferences() {
        Dialog<ButtonType> dialog = new Dialog<>(); dialog.initOwner(stage); dialog.setTitle("런처 설정");
        TextField clientId = new TextField(settings.clientId); TextField javaPath = new TextField(settings.javaPath); javaPath.setPromptText("비워두면 자동 설치 · 직접 지정 시 java.exe 전체 경로");
        Spinner<Integer> memory = new Spinner<>(1024, 32768, settings.memoryMb, 512); memory.setEditable(true);
        GridPane grid = form(new String[]{"Microsoft Client ID", "게임 Java 실행 파일", "게임 메모리 (MB)"}, clientId, javaPath, memory);
        Button logout = new Button("로그아웃"); logout.setOnAction(event -> { try { auth.logout(); account.setText("로그인하지 않음"); } catch (IOException ex) { error(ex); } });
        Button folder = new Button("데이터 폴더 열기"); folder.setOnAction(event -> { try { Desktop.getDesktop().open(store.root.toFile()); } catch (IOException ex) { error(ex); } });
        VBox content = new VBox(12, grid, new HBox(10, logout, folder), new Label("Minecraft·NeoForge 설치 파일과 모드는 서버별 인스턴스에서 관리합니다."));
        dialog.getDialogPane().setContent(content); dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) { settings.clientId = clientId.getText().strip(); settings.javaPath = javaPath.getText().strip(); settings.memoryMb = memory.getValue(); persist(); }
    }

    private void connect() {
        if (active != null && !active.isDone()) return;
        Models.ServerEntry server = list.getSelectionModel().getSelectedItem(); if (server == null) return;
        if (settings.clientId.isBlank()) { preferences(); if (settings.clientId.isBlank()) return; }
        work(() -> {
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
                var player = auth.authenticate(settings.clientId, this::progress);
                Net.checkCancelled();
                checkRunning(instance);
                progress("게임 실행 및 서버 접속");
                Process process = installer.launch(game, instance, server.address(), player, settings.memoryMb);
                JsonObject marker = new JsonObject(); marker.addProperty("pid", process.pid()); marker.addProperty("startedAt", process.info().startInstant().orElse(Instant.now()).toString());
                Store.write(instance.resolve("game-process.json"), Store.JSON.toJson(marker));
                FileLock runningLock = lock;
                Thread.ofVirtual().name("game-exit-watcher").start(() -> {
                    try { int exit = process.waitFor(); progress("게임 종료 · 코드 " + exit + " · 로그: " + instance.resolve("logs/launcher-game.log")); }
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
        Label validation = new Label(); validation.setWrapText(true); validation.setStyle("-fx-text-fill: #ffb4a2;");
        VBox box = new VBox(14, description, constraints, scroll, validation); box.setPrefWidth(800);
        dialog.getDialogPane().setContent(box); dialog.getDialogPane().getButtonTypes().addAll(ButtonType.APPLY, ButtonType.CANCEL);
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.APPLY)).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            List<String> errors = ModSync.validate(plan.info(), plan.candidates()); if (!errors.isEmpty()) { validation.setText(String.join("\n", errors)); event.consume(); }
        });
        dialog.setResultConverter(button -> button == ButtonType.APPLY);
        activeDialog = dialog; try { return dialog.showAndWait().orElse(false); } finally { activeDialog = null; }
    }
    @Override public void stop() { refresh.shutdownNow(); pings.shutdownNow(); if (active != null) active.cancel(true); }
}
