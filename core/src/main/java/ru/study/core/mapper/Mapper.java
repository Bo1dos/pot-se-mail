package ru.study.core.mapper;

public interface Mapper<E, D, T> {
    D toDomain(E entity);
    E toEntity(D domain);
    T toDto(D domain);
    D fromDto(T dto);
}