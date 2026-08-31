/* Absolute pointer in .rdata so the PE has a base reloc without a separate .data section. */
void *win_file_stat_keep __attribute__((section(".rdata"))) = &win_file_stat_keep;
