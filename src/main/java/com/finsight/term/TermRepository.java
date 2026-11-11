package com.finsight.term;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TermRepository extends JpaRepository<Term, Long> {

    @Override
    @org.springframework.cache.annotation.Cacheable(cacheNames="termDictionary", key="'all'")
    java.util.List<Term> findAll();

    @Override
    @org.springframework.cache.annotation.CacheEvict(cacheNames="termDictionary", allEntries=true)
    <S extends Term> S save(S entity);

    @Override
    @org.springframework.cache.annotation.CacheEvict(cacheNames="termDictionary", allEntries=true)
    <S extends Term> java.util.List<S> saveAll(Iterable<S> entities);

    Optional<Term> findByTermIgnoreCase(String term);
}
