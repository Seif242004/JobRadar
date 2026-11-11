package com.jobradar.ingestion.internal.source;

/**
 * One company job board on an ATS, as the careers resolver stored it.
 *
 * @param companyName {@code company.name}; used for the postings so they link to that company row
 * @param token       {@code company.board_token}: the board's public identifier on the ATS
 */
public record Board(String companyName, String token) {}
