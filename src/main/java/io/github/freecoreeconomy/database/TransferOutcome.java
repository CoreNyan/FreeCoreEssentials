package io.github.freecoreeconomy.database;

import java.math.BigDecimal;

public record TransferOutcome(TransferOutcome.Status status, BigDecimal sourceBalance, BigDecimal targetBalance) {
   public static TransferOutcome success(BigDecimal var0, BigDecimal var1) {
      return new TransferOutcome(TransferOutcome.Status.SUCCESS, var0, var1);
   }

   public static TransferOutcome sourceNotFound() {
      return new TransferOutcome(TransferOutcome.Status.SOURCE_NOT_FOUND, BigDecimal.ZERO, BigDecimal.ZERO);
   }

   public static TransferOutcome targetNotFound(BigDecimal var0) {
      return new TransferOutcome(TransferOutcome.Status.TARGET_NOT_FOUND, var0, BigDecimal.ZERO);
   }

   public static TransferOutcome insufficientFunds(BigDecimal var0, BigDecimal var1) {
      return new TransferOutcome(TransferOutcome.Status.INSUFFICIENT_FUNDS, var0, var1);
   }

   public enum Status {
      SUCCESS,
      SOURCE_NOT_FOUND,
      TARGET_NOT_FOUND,
      INSUFFICIENT_FUNDS;
   }
}
