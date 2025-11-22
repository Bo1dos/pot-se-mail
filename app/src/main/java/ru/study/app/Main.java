package ru.study.app;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import ru.study.ui.fx.controller.MainWindowController;

import java.util.ResourceBundle;

public class Main extends Application {

    // private ServiceLocator services;

    // @Override
    // public void init() throws Exception {
    //     super.init();
    //     // создаём ServiceLocator до старта UI
    //     services = ServiceLocator.createDefault();
    // }

    // @Override
    // public void start(Stage stage) throws Exception {
    //     ResourceBundle rb = ResourceBundle.getBundle("i18n.messages");
    //     FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainWindow.fxml"), rb);

    //     // ControllerFactory будет спрашивать ServiceLocator — для constructor injection
    //     loader.setControllerFactory(clazz -> services.getBean(clazz));
    //     var root = loader.load();

    //     Scene scene = new Scene((Parent) root);
    //     stage.setScene(scene);
    //     stage.setTitle("Mail Client");
    //     stage.show();
    // }

    // @Override
    // public void stop() throws Exception {
    //     // корректно завершить background executors и EMF
    //     services.shutdown();
    //     super.stop();
    // }

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainWindow.fxml"));
        var root = loader.load();
        Scene scene = new Scene((Parent) root, 1100, 700);
        stage.setScene(scene);
        stage.setTitle("MailClient — MVP UI");
        stage.show();
    }

    public static void main(String[] args) {
        launch();
    }
}