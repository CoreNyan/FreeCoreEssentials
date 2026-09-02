package io.github.freecoreeconomy.database;

public record BlessingSkinTexture(String hash, String type) {
   public boolean slim() {
      return "alex".equalsIgnoreCase(this.type);
   }
}
