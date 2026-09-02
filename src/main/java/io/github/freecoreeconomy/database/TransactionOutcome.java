package io.github.freecoreeconomy.database;

import java.math.BigDecimal;

public record TransactionOutcome(TransactionOutcome.Status status, BigDecimal balance) {
   public static TransactionOutcome success(BigDecimal var0) {
      return new TransactionOutcome(TransactionOutcome.Status.SUCCESS, var0);
   }

   public static TransactionOutcome accountNotFound() {
      return new TransactionOutcome(TransactionOutcome.Status.ACCOUNT_NOT_FOUND, BigDecimal.ZERO);
   }

   public static TransactionOutcome insufficientFunds(BigDecimal var0) {
      return new TransactionOutcome(TransactionOutcome.Status.INSUFFICIENT_FUNDS, var0);
   }

   public enum Status {
      SUCCESS,
      ACCOUNT_NOT_FOUND,
      INSUFFICIENT_FUNDS;
   }
}
