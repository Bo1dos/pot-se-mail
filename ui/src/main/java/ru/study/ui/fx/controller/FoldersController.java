package ru.study.ui.fx.controller;

import java.util.List;
import java.util.function.BiConsumer;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseEvent;
import ru.study.core.dto.AccountDTO;
import ru.study.core.dto.FolderDTO;
import ru.study.core.event.SyncCompletedEvent;
import ru.study.core.event.bus.EventBus;
import ru.study.service.api.AccountService;
import ru.study.service.api.FolderService;

public class FoldersController {

    @FXML public TreeView<FolderNode> foldersTree;

    private final AccountService accountService;
    private final FolderService folderService;
    private final EventBus eventBus;

    // callback: (accountId, folderName)
    private BiConsumer<Long, String> onFolderSelected = (a, f) -> {};

    public FoldersController(AccountService accSvc, FolderService folderSvc, EventBus eventBus) {
        this.accountService = accSvc;
        this.folderService = folderSvc;
        this.eventBus = eventBus;
    }

    @FXML
    public void initialize() {
        // subscribe so tree refreshes after sync
        eventBus.subscribe(SyncCompletedEvent.class, ev -> loadFolders());

        // selection handler
        foldersTree.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if (newV == null) return;
            FolderNode node = newV.getValue();
            if (node == null) return;
            if (node.isFolder()) {
                // notify parent with account id + folder server name
                onFolderSelected.accept(node.accountId(), node.serverName());
            } else {
                // clicked on account root — optionally select INBOX
            }
        });

        // initial load
        loadFolders();
    }

    public void setOnFolderSelected(BiConsumer<Long, String> handler) {
        if (handler != null) this.onFolderSelected = handler;
    }

    private void loadFolders() {
        List<AccountDTO> accounts = accountService.listAccounts();

        TreeItem<FolderNode> root = new TreeItem<>(FolderNode.root());
        for (AccountDTO acc : accounts) {
            FolderNode accNodeVal = FolderNode.account(acc.id(), acc.email());
            TreeItem<FolderNode> accNode = new TreeItem<>(accNodeVal);
            List<FolderDTO> folders = folderService.listFolders(acc.id());
            for (FolderDTO f : folders) {
                String local = f.localName() != null ? f.localName() : f.serverName();
                FolderNode fn = FolderNode.folder(acc.id(), f.serverName(), local);
                accNode.getChildren().add(new TreeItem<>(fn));
            }
            accNode.setExpanded(true);
            root.getChildren().add(accNode);
        }

        Platform.runLater(() -> {
            foldersTree.setRoot(root);
            foldersTree.setShowRoot(false);
        });
    }

    // small helper value object to carry node info in tree
    public static record FolderNode(Long accountId, String serverName, String display, boolean folder) {
        public static FolderNode root() { return new FolderNode(null, null, "Accounts", false); }
        public static FolderNode account(Long acc, String display) { return new FolderNode(acc, null, display, false); }
        public static FolderNode folder(Long acc, String serverName, String display) { return new FolderNode(acc, serverName, display, true); }
        public boolean isFolder() { return folder; }
        public Long accountId() { return accountId; }
        public String serverName() { return serverName; }
        @Override public String toString() { return display; }
    }
}