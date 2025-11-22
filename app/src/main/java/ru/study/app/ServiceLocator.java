// package ru.study.app;

// import ru.study.core.event.EventBus;
// import ru.study.service.controller.MailController;
// import ru.study.service.controller.impl.MailControllerImpl;
// import ru.study.ui.event.EventBusImpl;
// import ru.study.ui.fx.controller.*;

// import java.util.Map;
// import java.util.concurrent.ConcurrentHashMap;
// import java.util.function.Supplier;

// public class ServiceLocator {

//     private final Map<Class<?>, Supplier<?>> registry = new ConcurrentHashMap<>();

//     private final EventBus eventBus = new EventBusImpl();

//     // приватный ctor
//     private ServiceLocator() {
//         // register service-layer implementations
//         MailController mailController = new MailControllerImpl(/* inject repos/services */);
//         // register instances
//         registry.put(MailController.class, () -> mailController);
//         registry.put(EventBus.class, () -> eventBus);

//         // register controllers — use suppliers to create new controller instances OR singletons
//         registry.put(ru.study.ui.fx.controller.MainWindowController.class,
//                 () -> new MainWindowController(getBean(MailController.class), eventBus));
//         registry.put(LoginDialogController.class,
//                 () -> new LoginDialogController(getBean(MailController.class), eventBus));
//         registry.put(ru.study.ui.fx.controller.ComposerController.class,
//                 () -> new ComposerController(getBean(MailController.class), eventBus));

//         // ... register other controllers or use generic fallback (see getBean)
//     }

//     public static ServiceLocator createDefault() {
//         return new ServiceLocator();
//     }

//     @SuppressWarnings("unchecked")
//     public <T> T getBean(Class<T> clazz) {
//         Supplier<?> s = registry.get(clazz);
//         if (s != null) return (T) s.get();

//         // Fallback: try to instantiate via reflection (only no-arg ctor)
//         try {
//             return clazz.getDeclaredConstructor().newInstance();
//         } catch (Exception e) {
//             throw new IllegalStateException("No bean for " + clazz, e);
//         }
//     }

//     public EventBus getEventBus() {
//         return eventBus;
//     }

//     public void shutdown() {
//         // остановить executors, EMF и пр.
//         eventBus.shutdown();
//         // ... другие ресурсы
//     }
// }
