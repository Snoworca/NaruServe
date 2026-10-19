package com.snoworca.naruserve.spi;

import com.snoworca.naruserve.RestApp;

/** Bootstrap SPI behind {@link RestApp#create()}; the NaruServe module provides the one implementation. */
// @req FR-APP-001
public interface AppFactory {

    RestApp create();
}
