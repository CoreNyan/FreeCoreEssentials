package io.github.freecoreeconomy.vault;

public record TransferResponse(TransferResponse.Status status, double amount, double sourceBalance, double targetBalance, String message) {
   public boolean successful() {
      return this.status == TransferResponse.Status.SUCCESS;
   }

   public enum Status {
      SUCCESS,
      INVALID_AMOUNT,
      SAME_ACCOUNT,
      SOURCE_NOT_FOUND,
      TARGET_NOT_FOUND,
      INSUFFICIENT_FUNDS,
      DATABASE_FAILURE;
   }
}
