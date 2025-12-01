package ru.study.app;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import ru.study.persistence.util.EntityManagerFactoryProvider;

import java.util.ResourceBundle;

public class Main extends Application {

    private ServiceLocator services;

    @Override
    public void init() throws Exception {
        super.init();
        // Создаёт реальные сервисы, репозитории, eventBus, mailAdapter и т.д.
        services = ServiceLocator.createDefault();
    }

    @Override
    public void start(Stage stage) throws Exception {
        // локализация (если нужна)
        //ResourceBundle rb = ResourceBundle.getBundle("i18n.messages");

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainWindow.fxml"));

        // Контроллеры будут браться из ServiceLocator (constructor injection)
        loader.setControllerFactory(clazz -> services.getBean(clazz));

        Parent root = loader.load();
        Scene scene = new Scene(root, 1100, 700);

        // подключаем css, если есть
        try {
            scene.getStylesheets().add(getClass().getResource("/css/app.css").toExternalForm());
        } catch (Exception ignored) {}

        stage.setScene(scene);
        stage.setTitle("MailClient — MVP UI");
        stage.show();

        // (опционально) запустить автосинхронизацию
        // var sync = services.getBean(ru.study.service.api.SyncService.class);
        // sync.startAutoSync();

        var mps = services.getBean(ru.study.service.api.MasterPasswordService.class);
        if (mps.getCurrentMasterPassword().isEmpty()) {
            // show master password modal
            javafx.fxml.FXMLLoader masterPassLoader = new javafx.fxml.FXMLLoader(getClass().getResource("/fxml/MasterPasswordDialog.fxml"));
            masterPassLoader.setControllerFactory(clazz -> {
                if (clazz == ru.study.ui.fx.controller.MasterPasswordDialogController.class) {
                    return new ru.study.ui.fx.controller.MasterPasswordDialogController(mps, services.getBean(ru.study.core.event.bus.EventBus.class));
                }
                try { return clazz.getDeclaredConstructor().newInstance(); } catch (Exception ex) { throw new RuntimeException(ex); }
            });
            javafx.scene.Parent masterPassRoot = masterPassLoader.load();
            Stage dlg = new Stage();
            dlg.initOwner(stage);
            dlg.setScene(new javafx.scene.Scene(masterPassRoot));
            dlg.setTitle("Unlock / Initialize Master Password");
            dlg.initModality(javafx.stage.Modality.APPLICATION_MODAL);
            dlg.showAndWait();
        }
    }

    @Override
    public void stop() throws Exception {
        // корректно завершить сервисы, executors и закрыть EMF
        try {
            if (services != null) services.shutdown();
        } catch (Exception e) {
            System.err.println("Failed to shutdown services: " + e.getMessage());
            e.printStackTrace();
        }

        // гарантируем закрытие EntityManagerFactory
        try {
            EntityManagerFactoryProvider.close();
        } catch (Exception e) {
            System.err.println("Failed to close EMF: " + e.getMessage());
            e.printStackTrace();
        }

        super.stop();
    }

    //mvn -pl app javafx:run
    public static void main(String[] args) {
        launch(args);
    }
}
