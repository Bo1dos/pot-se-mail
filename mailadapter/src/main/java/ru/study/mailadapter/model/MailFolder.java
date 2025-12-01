package ru.study.mailadapter.model;

public final class MailFolder {
    private final String name;
    private final int messageCount;

    public MailFolder(String name, int messageCount) {
        this.name = name;
        this.messageCount = messageCount;
    }

    public String getName() { return name; }
    public int getMessageCount() { return messageCount; }

    @Override
    public String toString() { return name + " (" + messageCount + ")"; }
}
