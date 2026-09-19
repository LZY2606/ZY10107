package com.molmap.service;

/**
 * Lock request carrying the optimistic-lock version the browser last saw. Two
 * browsers editing the same confirmed case race on this value; the loser gets a
 * conflict containing the winner's content and can re-merge.
 */
public record LockRequest(long caseVersion, int candidateIndex) {}
