package ru.study.core.model;

import lombok.Getter;
import lombok.ToString;
import ru.study.core.util.EmailUtils;
import ru.study.core.exception.ValidationException;

import java.util.Objects;

@Getter
@ToString
public final class EmailAddress {
    private final String address;

    public EmailAddress(String address) {
        if (address == null || !EmailUtils.isValid(address)) {
            throw new ValidationException("Invalid email address: " + address);
        }
        this.address = address;
    }

    public String value() { 
        return address; 
    }

    @Override 
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EmailAddress)) return false;
        EmailAddress that = (EmailAddress) o;
        return address.equalsIgnoreCase(that.address);
    }

    @Override 
    public int hashCode() { 
        return Objects.hash(address.toLowerCase()); 
    }
}