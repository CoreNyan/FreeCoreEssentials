package io.github.freecoreeconomy.database;

public record BedrockAccountResult(BedrockAccountResult.Status status, String accountName, String email, boolean passwordSet, boolean nameAdjusted) {
   public enum Status {
      CREATED,
      UNCHANGED,
      RENAMED;
   }
}
