package ru.study.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.study.core.event.bus.EventBus;
import ru.study.mailadapter.api.MailAdapter;
import ru.study.mailadapter.impl.JavaMailAdapter;
import ru.study.crypto.provider.CryptoProviderFactory;
import ru.study.crypto.provider.DefaultCryptoProviderFactory;
import ru.study.crypto.impl.keymgmt.KeyManagementImpl;
import ru.study.crypto.api.KeyManagement;
import ru.study.persistence.util.EntityManagerFactoryProvider;
import ru.study.persistence.repository.impl.*;
import ru.study.persistence.repository.api.*;
import ru.study.service.api.*;
import ru.study.service.client.KeyServerClient;
import ru.study.service.client.KeyServerClientImpl;
import ru.study.service.impl.*;
import ru.study.ui.event.EventBusImpl;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Simple ServiceLocator for MVP wiring of real implementations.
 * NOTE: For simplicity repository implementations are created with a long-lived EntityManager here.
 * This is acceptable for quick MVP; later replace with per-method EM or delegating repos.
 */
public class ServiceLocator {

    private static final Logger log = LoggerFactory.getLogger(ServiceLocator.class);

    private final Map<Class<?>, Supplier<?>> registry = new ConcurrentHashMap<>();

    // singletons
    private final EventBus eventBus;
    private final CryptoProviderFactory cryptoProviderFactory;
    private final KeyManagement keyManagement;
    private final MailAdapter mailAdapter;

    // services
    private final AccountService accountService;
    private final NotificationService notificationService;
    private final AttachmentService attachmentService;
    private final MasterPasswordService masterPasswordService;
    private final KeyManagementService keyManagementService;
    private final SyncService syncService;
    private final MailService mailService;
    private final KeyServerClient keyServerClient;

    public ServiceLocator() {
        log.info("Creating ServiceLocator (MVP wiring)");

        // infra / singletons
        this.eventBus = new EventBusImpl();
        this.cryptoProviderFactory = new DefaultCryptoProviderFactory();
        this.keyManagement = new KeyManagementImpl(); // direct instance of KeyManagement impl
        this.mailAdapter = new JavaMailAdapter();

        // create long-lived EntityManager for repo impls (MVP)
        EntityManager em = EntityManagerFactoryProvider.createEntityManager();

        // repositories (simple impls that take EntityManager)
        AccountRepository accountRepository = new AccountRepositoryImpl(em);
        KeyRepository keyRepository = new KeyRepositoryImpl(em);
        MasterPasswordRepository masterPasswordRepository = new MasterPasswordRepositoryImpl(em);
        MessageRepository messageRepository = new MessageRepositoryImpl(em);
        FolderRepository folderRepository = new FolderRepositoryImpl(em);
        AttachmentRepository attachmentRepository = new AttachmentRepositoryImpl(em);
        MessageWrappedKeyRepository messageWrappedKeyRepository = new MessageWrappedKeyRepositoryImpl(em);

        // notification
        this.notificationService = new NotificationServiceImpl(eventBus);

        // basic service instances (inject dependencies according to constructors you provided)
        this.attachmentService = new AttachmentServiceImpl(notificationService);

        this.masterPasswordService = new MasterPasswordServiceImpl(
                keyRepository, masterPasswordRepository, keyManagement, notificationService, eventBus
        );

        this.accountService = new AccountServiceImpl(
                accountRepository, keyManagement, masterPasswordService, mailAdapter
        );

        this.keyManagementService = new KeyManagementServiceImpl(
                keyRepository, accountRepository, cryptoProviderFactory.getAsymmetricCipher("RSA"), keyManagement, eventBus
        );

        // KeyServerClient impl — try default impl if present, otherwise provide a dumb stub
        KeyServerClient tmpKeyServerClient;
        try {
            tmpKeyServerClient = new KeyServerClientImpl("http://localhost:8081");
        } catch (Throwable t) {
            log.warn("KeyServerClientImpl ctor failed — using stub", t);
            tmpKeyServerClient = new KeyServerClient() {
                @Override public java.util.Optional<ru.study.core.dto.KeyDTO> findKeyByEmail(String email) { return java.util.Optional.empty(); }
                @Override public ru.study.core.dto.KeyDTO uploadPublicKey(String email, String publicKeyPem) { throw new UnsupportedOperationException(); }
                @Override public void verifyKeyByToken(String token) { throw new UnsupportedOperationException(); }
            };
        }
        this.keyServerClient = tmpKeyServerClient;

        this.mailService = new MailServiceImpl(
                accountService, mailAdapter, attachmentService, keyServerClient,
                cryptoProviderFactory, keyManagementService, masterPasswordService, notificationService, eventBus
        );

        this.syncService = new SyncServiceImpl(mailAdapter, notificationService, eventBus, accountService);

        // register beans for controller factory and for getBean lookup
        registerSingleton(EventBus.class, () -> eventBus);
        registerSingleton(CryptoProviderFactory.class, () -> cryptoProviderFactory);
        registerSingleton(KeyManagement.class, () -> keyManagement);
        registerSingleton(MailAdapter.class, () -> mailAdapter);

        registerSingleton(AccountService.class, () -> accountService);
        registerSingleton(NotificationService.class, () -> notificationService);
        registerSingleton(AttachmentService.class, () -> attachmentService);
        registerSingleton(MasterPasswordService.class, () -> masterPasswordService);
        registerSingleton(KeyManagementService.class, () -> keyManagementService);
        registerSingleton(MailService.class, () -> mailService);
        registerSingleton(SyncService.class, () -> syncService);
        registerSingleton(KeyServerClient.class, () -> keyServerClient);

        // also expose repositories if needed by other modules
        registerSingleton(AccountRepository.class, () -> accountRepository);
        registerSingleton(KeyRepository.class, () -> keyRepository);
        registerSingleton(MasterPasswordRepository.class, () -> masterPasswordRepository);
        registerSingleton(MessageRepository.class, () -> messageRepository);
        registerSingleton(FolderRepository.class, () -> folderRepository);
        registerSingleton(AttachmentRepository.class, () -> attachmentRepository);
        registerSingleton(MessageWrappedKeyRepository.class, () -> messageWrappedKeyRepository);


        // register controllers so FXMLLoader obtains constructor-injected instances
        registerSingleton(ru.study.ui.fx.controller.MainWindowController.class,
                () -> new ru.study.ui.fx.controller.MainWindowController(mailService, eventBus, accountService, masterPasswordService, syncService));

        registerSingleton(ru.study.ui.fx.controller.ComposerController.class,
                () -> new ru.study.ui.fx.controller.ComposerController(mailService, eventBus, accountService));

        registerSingleton(ru.study.ui.fx.controller.InboxController.class,
                () -> new ru.study.ui.fx.controller.InboxController(mailService, eventBus));

        // MessageViewController has no-arg constructor — can be newed directly
        registerSingleton(ru.study.ui.fx.controller.MessageViewController.class,
                () -> new ru.study.ui.fx.controller.MessageViewController());

        // other UI controllers (optional) — register if you implemented constructor injection:
        registerSingleton(ru.study.ui.fx.controller.FoldersController.class,
                () -> new ru.study.ui.fx.controller.FoldersController());
        registerSingleton(ru.study.ui.fx.controller.LoginDialogController.class,
                () -> new ru.study.ui.fx.controller.LoginDialogController());
        registerSingleton(ru.study.ui.fx.controller.ProgressController.class,
                () -> new ru.study.ui.fx.controller.ProgressController());

        registerSingleton(ru.study.ui.fx.controller.MasterPasswordDialogController.class,
                () -> new ru.study.ui.fx.controller.MasterPasswordDialogController(masterPasswordService, eventBus));

        registerSingleton(ru.study.ui.fx.controller.AccountDialogController.class,
                () -> new ru.study.ui.fx.controller.AccountDialogController(accountService, eventBus));




        log.info("ServiceLocator initialized (MVP)");
    }

    private <T> void registerSingleton(Class<T> clazz, Supplier<?> supplier) {
        registry.put(clazz, supplier);
    }

    @SuppressWarnings("unchecked")
    public <T> T getBean(Class<T> clazz) {
        Supplier<?> s = registry.get(clazz);
        if (s != null) return (T) s.get();
        // fallback: try no-arg ctor
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("No bean for " + clazz + " and cannot instantiate", e);
        }
    }

    public void shutdown() {
        log.info("ServiceLocator shutdown: stopping EventBus and closing EMF");
        try { eventBus.shutdown(); } catch (Exception ignored) {}
        try { EntityManagerFactoryProvider.close(); } catch (Exception ignored) {}
    }


    public static ServiceLocator createDefault() {
        return new ServiceLocator();
    }
}
