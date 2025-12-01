package ru.study.mailadapter.model;

public final class AccountConfig {
    private final String email;
    private final String username;
    private final String password; // plaintext for connect (service decrypts)
    private final String imapHost;
    private final int imapPort;
    private final boolean imapSsl;
    private final String smtpHost;
    private final int smtpPort;
    private final boolean smtpSsl;

    public AccountConfig(String email, String username, String password,
                         String imapHost, int imapPort, boolean imapSsl,
                         String smtpHost, int smtpPort, boolean smtpSsl) {
        this.email = email;
        this.username = username;
        this.password = password;
        this.imapHost = imapHost;
        this.imapPort = imapPort;
        this.imapSsl = imapSsl;
        this.smtpHost = smtpHost;
        this.smtpPort = smtpPort;
        this.smtpSsl = smtpSsl;
    }

    public String getEmail() { return email; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public String getImapHost() { return imapHost; }
    public int getImapPort() { return imapPort; }
    public boolean isImapSsl() { return imapSsl; }
    public String getSmtpHost() { return smtpHost; }
    public int getSmtpPort() { return smtpPort; }
    public boolean isSmtpSsl() { return smtpSsl; }
}
